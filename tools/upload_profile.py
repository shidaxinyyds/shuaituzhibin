"""跨游戏特征知识库云端热更发布工具 (upload_profile.py)

作用：
  读取本地游戏知识库 JSON 产物（如 pipeline/rate_of_land.json），
  自动生成 Supabase PostgreSQL 的 upsert 导入语句。
  在 Supabase SQL Editor 执行后，全网手机端辅助即刻静默热更新生效！无需重新打包发版 APK！

为什么本工具必须“拒绝发布”
------------------------------
云端知识库是被全网客户端**静默采纳**的配置：一旦推错，没有任何人会收到报错，
而所有手机都会开始跑一份坏规则。本工具是这条链路上唯一的人工操作点，所以它
不能只做“把 json 包成 SQL”这一件事，它必须在生成 SQL **之前**拦住三类真实事故：

  闸门 1（字段齐全）：缺 game_id / game_name / profile_version 直接拒绝。
    以前这里写的是 data.get("profile_version", "1.0.0")——一份没版本号的配置
    会以 1.0.0 上云，客户端一比就判定「不比内置新」而不采纳，发布者在后台
    看不出任何异常，以为已经生效了。这是最阴的一种失效：做了，但没做。

  闸门 2（产物新鲜）：待发布文件必须能被导出器从内置库原样复现。
    否则等于在发布一份手改过的、与代码不同源的规则，下次重导就会静默抹掉。

  闸门 3（版本单调）：依据 publish_ledger 记录的上一次发布结果：
    - 版本号倒退 → 拒绝（会把线上较新的配置覆盖成旧的）；
    - 版本号相同但内容变了 → 拒绝。客户端只采纳**严格更更新**的版本，
      改了内容不升版本号 = 这次发布对全网永远无效。

> publish_ledger 记录在**本机**，它拦不住“换一台电脑发布”，但拦得住绝大多数
> 真实的单人重复发布场景。它不是安全边界，只是把已知的静默失效变成明确报错。

用法示例：
  python tools/export_profile.py                                  # 先改内置库、再导出
  python tools/upload_profile.py --json pipeline/rate_of_land.json # 再生成发布 SQL
"""

import argparse
import hashlib
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import export_profile as ex  # noqa: E402  复用导出器：发布的那一份必须就是导出来的那一份

# 刻意不在模块顶层包 sys.stdout：本模块会被 export_profile / run_all_checks
# 当作库引入，顶层副作用会把外层调度者的重定向弄坏。包装动作在 main() 里做。

# 已发布台账（记录本工具上一次生成过哪个版本、内容指纹是什么）。
# 它是**本机运行状态**，不进仓库（.gitignore 已屏蔽）：跨机共享一份台账
# 会让另一台机器上的正常发布被“版本单调”误杀。
LEDGER_REL = os.path.join("pipeline", "publish_ledger.json")


def _die(msg, hint=None):
    print("\n❌ 拒绝发布：" + msg)
    if hint:
        print("   → " + hint)
    print("   （宁可让你现在停下来，也不能让一份有问题的配置被全网客户端静默采纳。）\n")
    sys.exit(1)


def _sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def compare_version(a, b):
    """与端上 GameProfile.compareVersion **逐字同语义**：

      端上：v.trim().split('.', '-', '_', ' ').map { it.trim().toIntOrNull() ?: 0 }
      再逐段比，缺段补 0。

    两个容易踩的细节（我之前就在这里写过不一致的版本）：
      * 分隔符不只是 `.`，还有 `-` / `_` / 空格；
      * 非纯数字段（如 “v2026”）在端上是 **0**，不是前导的 2026。
    闸门与端上算法不一致比没有闸门更危险：它会“这里判可以发、端上就是不采纳”。
    """
    def parts(v):
        out = []
        seg = str(v).strip()
        for ch in ("-", "_", " "):
            seg = seg.replace(ch, ".")
        for piece in seg.split("."):
            piece = piece.strip()
            try:
                out.append(int(piece))
            except ValueError:
                out.append(0)
        return out

    pa, pb = parts(a), parts(b)
    for i in range(max(len(pa), len(pb))):
        x = pa[i] if i < len(pa) else 0
        y = pb[i] if i < len(pb) else 0
        if x != y:
            return 1 if x > y else -1
    return 0


def _ledger_path():
    return os.path.join(ex.REPO, LEDGER_REL)


def _load_ledger():
    p = _ledger_path()
    if not os.path.exists(p):
        return {}
    try:
        with io.open(p, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception as e:
        # 台账坏了不能反过来拦住正常发布，也不能当作「从未发布」——
        # 那会让版本单调闸门静默失效。明确报错，让人决定怎么处理。
        _die("发布台账 %s 读取失败：%s" % (LEDGER_REL, e),
             "台账坏了就删掉重建（它只用于防重复/防倒退，不是数据源头）")


def _record_publish(ledger, game_id, version, raw_text):
    ledger[game_id] = {
        "version": version,
        "sha256": _sha(raw_text),
        "note": "由 upload_profile.py 生成发布 SQL 时写入；代表本机已对外发过这一版。",
    }
    path = _ledger_path()
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def _require_field(data, key):
    val = data.get(key)
    if not isinstance(val, str) or not val.strip():
        _die("产物里 %s 缺失或为空（实际值：%r）" % (key, val),
             "补齐内置知识库里的对应字段后重跑 export_profile.py，不要在 JSON 上手填")
    return val


def _gate_freshness(json_path, game_id, raw_text):
    """待发布文件必须就是登记过的产物，且能从内置库原样复现。"""
    rel = ex.PRODUCTS.get(game_id)
    if not rel:
        _die("game_id=%s 没有在导出器 PRODUCTS 里登记云端产物路径" % game_id,
             "声称支持多游戏热更就得先为它登记产物；否则根本无「发布」一说")

    want_abs = os.path.normpath(os.path.abspath(os.path.join(ex.REPO, rel)))
    got_abs = os.path.normpath(os.path.abspath(json_path))
    if got_abs != want_abs:
        _die("待发布文件不是 %s 登记的产物路径（%s）" % (game_id, rel),
             "不要发布桌面上拷出去的旧副本；只发布仓库里由 export_profile.py 导出的那一份")

    want = ex.dump(ex.build_json(ex.load_builtin(game_id)))
    if raw_text != want:
        _die("产物与内置知识库不一致（陈旧或被手改过）", "先跑：python tools/export_profile.py 再发布")


def _gate_ledger(ledger, game_id, version, raw_text):
    prev = ledger.get(game_id) or {}
    prev_ver = prev.get("version") or ""
    if not prev_ver:
        return
    cmp_res = compare_version(version, prev_ver)
    if cmp_res < 0:
        _die("版本号倒退：本次 %s 比上次已发布的 %s 还旧" % (version, prev_ver),
             "那会把线上较新的配置覆盖成旧规则；内置库里把 profileVersion 升上去")
    if cmp_res == 0 and prev.get("sha256") and prev["sha256"] != _sha(raw_text):
        _die("内容变了但版本号没升（仍是 %s）" % version,
             "客户端只采纳严格更新的版本；同号改版对全网永远不生效。"
             "请先把内置库的 profileVersion 升一位，再重跑 export_profile.py")


def _selftest():
    """发布闸门的反例自测：已知事故必须逐条被咬住，正例不许误杀。

    为什么需要它：这道闸门只在人手动发布时才跑，一旦它自己坏了（比较写反、
    台账读错），后果是“闸门存在但形同虚设”，而且没人会发现。
    自测不写任何仓库文件，因此可以当必需项进 CI 常绿跑。
    """
    import contextlib

    fails = []
    ran = [0]

    def expect_block(label, fn):
        ran[0] += 1
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                fn()
        except SystemExit:
            return
        fails.append("%s：本该拦截，却放行了" % label)

    def expect_pass(label, fn):
        ran[0] += 1
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                fn()
        except SystemExit:
            detail = " / ".join(l.strip() for l in buf.getvalue().splitlines() if l.strip())[:120]
            fails.append("%s：本该放行，却拦了（%s）" % (label, detail))

    rel = ex.PRODUCTS.get("stzb")
    if not rel:
        print("❌ 自测无法进行：PRODUCTS 里没有 stzb 登记")
        return 1
    path = os.path.join(ex.REPO, rel)
    if not os.path.exists(path):
        print("❌ 自测无法进行：产物 %s 不存在（先跑 export_profile.py）" % rel)
        return 1
    with io.open(path, encoding="utf-8") as f:
        raw = f.read()
    tampered = raw.replace('"max_stamina": 120', '"max_stamina": 999')
    if tampered == raw:
        print("❌ 自测无效：注入用的小改没生效（产物的字段形式改了）")
        return 1

    # 闸 1：字段齐全
    expect_block("缺 game_name", lambda: _require_field({"game_id": "stzb"}, "game_name"))
    expect_block("版本号是空串", lambda: _require_field({"profile_version": "  "}, "profile_version"))
    expect_pass("字段齐全", lambda: _require_field({"game_id": "stzb"}, "game_id"))

    # 闸 2：产物新鲜
    expect_block("发布登记表外的副本", lambda: _gate_freshness("pipeline/_copy.json", "stzb", raw))
    expect_block("产物被手改一处数字", lambda: _gate_freshness(path, "stzb", tampered))
    expect_block("game_id 未登记", lambda: _gate_freshness(path, "dnf", raw))
    expect_pass("刚导出的真产物", lambda: _gate_freshness(path, "stzb", raw))

    # 闸 3：版本单调
    led = {"stzb": {"version": "2026.10.2", "sha256": _sha(raw)}}
    expect_block("版本倒退", lambda: _gate_ledger(led, "stzb", "2026.10.1", raw))
    expect_block("同号换内容", lambda: _gate_ledger(led, "stzb", "2026.10.2", tampered))
    expect_pass("同号同内容（重跑）", lambda: _gate_ledger(led, "stzb", "2026.10.2", raw))
    expect_pass("正常升版", lambda: _gate_ledger(led, "stzb", "2026.10.3", tampered))
    expect_pass("从未发布过（无台账）", lambda: _gate_ledger({}, "sgz", "0.0.1", raw))

    # 闸 3 的地基：版本比较必须与端上 GameProfile.compareVersion 逐字同语义。
    # 期望值按端上规则手算：trim 后按 . - _ 空格 切段，每段 toIntOrNull() ?: 0。
    version_cases = [
        ("2026.10.2", "2026.10.1", 1),
        ("2026.10.2", "2026.10.2", 0),
        ("2026.10.2", "2026.10.3", -1),
        ("2026.10", "2026.10.0", 0),
        ("v2026.3", "2026.3.0", -1),      # 非纯数字段计 0，不是前导的 2026
        ("2026.10.10", "2026.10.9", 1),   # 字典序会误判成 -1
        ("2026-10-2", "2026.10.2", 0),    # 分隔符不只是一个点
        ("2026_10_2", "2026.10.2", 0),
        ("2026.10.2 ", "2026.10.2", 0),
        ("", "2026.10.2", -1),
        ("2026..10", "2026.0.10", 0),     # 空段计 0
    ]
    for a, b, want in version_cases:
        ran[0] += 1
        got = compare_version(a, b)
        if got != want:
            fails.append("compare_version(%r, %r) = %d，端上语义应为 %d" % (a, b, got, want))

    if fails:
        print("❌ 发布闸门自测发现 %d 处问题：" % len(fails))
        for f in fails:
            print("   - " + f)
        return 1
    print("[OK] 发布闸门自测通过：%d 条用例——反例全部拦位、正例无误杀，"
          "版本比较与端上逐条一致" % ran[0])
    return 0


def main():
    # 把输出切成 UTF-8，与 run_all_checks 里同一手法：用 reconfigure 而不是
    # 套一层 TextIOWrapper(sys.stdout.buffer)——外层调度者（run_all_checks）会把
    # sys.stdout 换成 StringIO 来捕获输出，那时根本没有 .buffer，会直接报错。
    if sys.platform == "win32" and hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    parser = argparse.ArgumentParser(description="跨游戏知识库热更发布工具")
    parser.add_argument("--json", help="知识库 JSON 产物路径 (如 pipeline/rate_of_land.json)")
    parser.add_argument("--selftest", action="store_true",
                        help="只跑发布闸门的反例自测（不读不改仓库文件，供 CI 常绿）")
    args = parser.parse_args()

    if args.selftest:
        return _selftest()

    if not args.json:
        _die("没给 --json", "用法：python tools/upload_profile.py --json pipeline/rate_of_land.json")

    if not os.path.exists(args.json):
        print(f"❌ 错误：指定的文件不存在: {args.json}")
        sys.exit(1)

    with io.open(args.json, "r", encoding="utf-8") as f:
        raw_text = f.read()
    try:
        data = json.loads(raw_text)
    except Exception as e:
        print(f"❌ 错误：JSON 文件解析失败: {e}")
        sys.exit(1)

    if not isinstance(data, dict) or "game_id" not in data:
        _die("这不是一个知识库产物（找不到 game_id）：%s" % args.json,
             "pipeline/*.assets 下的其它 JSON（如 rate_of_land 的特征图清单）不能当知识库发布")

    game_id = _require_field(data, "game_id")
    game_name = _require_field(data, "game_name")
    version = _require_field(data, "profile_version")

    ledger = _load_ledger()
    _gate_freshness(args.json, game_id, raw_text)
    _gate_ledger(ledger, game_id, version, raw_text)

    json_str_escaped = json.dumps(data, ensure_ascii=False).replace("'", "''")

    sql = f"""-- ==========================================================
-- 游戏知识库云端热更导入脚本
-- 目标游戏: {game_name} ({game_id}) | 版本号: {version}
-- 请复制以下 SQL 到 Supabase SQL Editor 执行：
-- ==========================================================
INSERT INTO public.game_profiles (game_id, game_name, version, profile_json, updated_at)
VALUES (
  '{game_id}',
  '{game_name}',
  '{version}',
  '{json_str_escaped}'::jsonb,
  now()
)
ON CONFLICT (game_id) DO UPDATE
SET
  game_name = EXCLUDED.game_name,
  version = EXCLUDED.version,
  profile_json = EXCLUDED.profile_json,
  updated_at = now();
"""

    print("\n" + "=" * 60)
    print(f"【知识库发布就绪】游戏: {game_name} ({game_id}) | 版本: {version}")
    print("=" * 60)
    print(sql)
    print("=" * 60)
    print("💡 提示：将上方 SQL 粘贴到 Supabase SQL Editor 执行，全网客户端点击【检查云端热更】即可自动完成静默热更！\n")

    # 过了全部闸门才记账：下次再发同一游戏时，“版本单调”那条才有对比基准。
    _record_publish(ledger, game_id, version, raw_text)
    print("🗂  已记录发布台账：%s（%s -> %s）\n" % (LEDGER_REL, game_id, version))

if __name__ == "__main__":
    main()
