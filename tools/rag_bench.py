#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P5 实测：端侧 RAG 检索到底需不需要换索引（HNSW 图 / sqlite-vec）
================================================================

这个脚本存在的理由：`SlgRagEngine` 的类注释写着"全量精确扫比图索引划算"，
`tools/RAG_INDEX_MIGRATION_ASSESSMENT.md` 写着结论与阈值。**注释和文档都会烂**，
能被复跑的数字不会。所以这里只做两件事：

1. 【测】从真实资产 `slg_knowledge_vector_hnsw.bin` 读出条目数/维度/文本量/图区邻接，
   并按 Kotlin 里那段余弦循环的**同构写法**（纯 Python 逐元素循环，不上 numpy）
   实测不同候选池规模下的单次查询耗时；
2. 【判】按文档里那份**机读阈值行**（`BENCH-THRESHOLDS:`）对一遍，
   输出 STAY / MIGRATE，并顺手查文档与真实资产有没有各说各话。

判据为什么是"乘加比值"而不是"多少毫秒"
--------------------------------------
本机是 Windows PC + CPython，端侧是 ARM 大核 + ART JIT。同一份逐元素循环
在这两边的绝对耗时差好几倍，且这台机器上根本没有 onnxruntime，
bge 的查询侧 embedd 延迟无法在此实测。所以**触发迁移的判据必须与机器无关**：
扫描的乘加次数 / 一次查询 embedd 的乘加次数 ≥ 阈值。耗时表照样打出来，
但它只作参考口径，不参与裁决。

退出码
------
0 = STAY（维持全量精确扫，不引入 sqlite-vec）
1 = MIGRATE（越过阈值，需要人工按文档决策；这是**评估结论**，不是构建失败）
2 = 闸门自己不可信（资产缺失 / 解析异常 / 文档与资产口径漂移）
"""

import argparse
import json
import math
import os
import re
import struct
import sys
import time
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ASSET = os.path.join(REPO, "client", "app", "src", "main", "assets",
                     "models", "slg_knowledge_vector_hnsw.bin")
CORPUS = os.path.join(HERE, "a_plus_plus", "corpus_stzb.jsonl")
DOC = os.path.join(HERE, "RAG_INDEX_MIGRATION_ASSESSMENT.md")

# 阈值唯一来源。文档里必须有一行机读标记与这里逐字段相同，
# check_doc_drift 会逐字段比对 —— 因为"文档抄一遍、代码抄一遍"必然漂移。
THRESHOLDS = {
    # 一次 bge-small-zh INT8 查询侧 embedd 的乘加次数（量级估算，非实测）：
    # 模型文件 23,375 KB，INT8 权重按 1 字节/参数 ⇒ ~2×10^7 参数。
    # 这个数只用来做**量级**对比，写死成 10 的幂更诚实，所以取 20000000。
    "embed_mul_adds": 20000000,
    # 扫描乘加占 embedd 乘加的比例到这个数，图索引才开始能省下可感知的延迟。
    "scan_ratio_migrate": 0.10,
    # 截断防线：候选数一旦超过它，任何"按遍历顺序截断"的实现都会静默丢条目。
    # 语料到这个规模之前，全量扫根本碰不到截断（当前实测 95 条）。
    "truncation_guard": 4096,
}

DEVICE_NOTES = (
    "本机 = Windows PC + CPython；端侧 = ARM 大核 + ART JIT。同样的逐元素循环，"
    "ART 一般比 CPython 快数倍，所以下面这张表的 ms 是端侧的**上界参考**，不是实测；"
    "它不参与裁决，裁决只看与机器无关的乘加比值。"
)


def human(n):
    return "%.1f KB" % (n / 1024.0) if n < 1048576 else "%.2f MB" % (n / 1048576.0)


def load_asset(path=ASSET):
    """按端侧解析器的同一容器格式读回来：头 + 条目 + 尾部 HNSW 第 0 层图区。"""
    with open(path, "rb") as fh:
        raw = fh.read()
    magic, version, count, dim = struct.unpack_from("<16sIII", raw, 0)
    off = 28
    entries = []
    text_bytes = 0
    for _ in range(count):
        lens = struct.unpack_from("<6I", raw, off)
        off += 24
        blobs = []
        for ln in lens:
            blobs.append(raw[off:off + ln])
            off += ln
        text_bytes += sum(lens)
        vec = list(struct.unpack_from("<%df" % dim, raw, off))
        off += dim * 4
        entries.append({
            "id": blobs[0].decode("utf-8", "replace"),
            "category": blobs[1].decode("utf-8", "replace"),
            "keywords": [k.strip() for k in
                         blobs[3].decode("utf-8", "replace").split(",") if k.strip()],
            "vector": vec,
        })

    graph_bytes = 0
    degrees = []
    adjacency = []
    if version == 2 and off + 4 <= len(raw):
        (graph_bytes,) = struct.unpack_from("<I", raw, off)
        p = off + 4
        end = p + graph_bytes
        while p + 4 <= end:
            (deg,) = struct.unpack_from("<I", raw, p)
            p += 4
            nbs = list(struct.unpack_from("<%dI" % deg, raw, p)) if deg else []
            p += 4 * deg
            degrees.append(deg)
            adjacency.append(nbs)
        if p != end:
            raise ValueError("图区解析停在 %d，声明到 %d（容器损坏或生成器口径漂移）" % (p, end))
    consumed = off + 4 + graph_bytes if version == 2 else off
    return {
        "path": path, "size": len(raw),
        "magic": magic.rstrip(b"\x00").decode("ascii", "replace"),
        "version": version, "items": count, "dim": dim,
        "vec_bytes": count * dim * 4, "text_bytes": text_bytes,
        "entries": entries, "graph_bytes": graph_bytes,
        "degrees": degrees, "adjacency": adjacency,
        "leftover_bytes": len(raw) - consumed,
    }


def corpus_line_count(path=CORPUS):
    if not os.path.isfile(path):
        return None
    n = 0
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                json.loads(line)  # 坏行必须炸，别让"行数"变成假指标
                n += 1
    return n


def cosine(v1, v2):
    """SlgRagEngine.computeCosineSimilarity 的逐行同构（含那个 min(len) 截断）。"""
    ln = min(len(v1), len(v2))
    dot = n1 = n2 = 0.0
    for i in range(ln):
        a = v1[i]
        b = v2[i]
        dot += a * b
        n1 += a * a
        n2 += b * b
    den = (n1 ** 0.5) * (n2 ** 0.5)
    return 0.0 if den == 0 else dot / den


def brute_scan(query_vec, pool, top_k=3, min_sim=0.0):
    """search() 通道 B 的同构：全量扫 + 卡下限 + 定序取 topK。"""
    scored = [(cosine(query_vec, e["vector"]), e["id"]) for e in pool]
    hits = [t for t in scored if t[0] >= min_sim]
    hits.sort(key=lambda t: -t[0])
    return hits[:top_k]


def measure_scan(pool, query_vec, reps=5):
    best = float("inf")
    for _ in range(reps):
        t0 = time.perf_counter()
        brute_scan(query_vec, pool)
        best = min(best, time.perf_counter() - t0)
    return best * 1000.0


def grow(entries, target):
    """合成放大：只为看复杂度趋势，条目内容不代表线上召回，文档也这样标注。"""
    if target <= len(entries):
        return list(entries)
    out = []
    while len(out) < target:
        src = entries[len(out) % len(entries)]
        out.append({"id": "%s#%d" % (src["id"], len(out)), "category": src["category"],
                    "keywords": src["keywords"], "vector": list(src["vector"])})
    return out


def pseudo_ann_candidate_set(n, adjacency=None, cutoff=None):
    """复刻**已被删除**的那条分支：从全部节点起 BFS 收候选，超过 cutoff 就停。

    返回 (候选数, 被截断静默丢掉的条数)。起点集已经是全图，
    所以候选集恒等于"全量扫"——它一次余弦都没省，只是多跑了一趟遍历。
    """
    cutoff = THRESHOLDS["truncation_guard"] if cutoff is None else cutoff
    seen = set()
    order = []
    q = deque(range(n))
    while q and len(seen) <= cutoff:
        cur = q.popleft()
        if cur in seen:
            continue
        seen.add(cur)
        order.append(cur)
        for nb in (adjacency[cur] if adjacency and cur < len(adjacency) else []):
            if nb not in seen:
                q.append(nb)
    return len(order), max(0, n - cutoff)


def scan_mul_adds(pool, dim):
    return pool * dim


def judge(pool, dim):
    """与本机无关的裁决：扫描乘加 vs 一次查询 embedd 乘加。"""
    ratio = scan_mul_adds(pool, dim) / float(THRESHOLDS["embed_mul_adds"])
    if pool >= THRESHOLDS["truncation_guard"]:
        return "MIGRATE", ratio
    return ("MIGRATE" if ratio >= THRESHOLDS["scan_ratio_migrate"] else "STAY"), ratio


def assess(asset, corpus_lines, scales=(42, 200, 1000, 2000, 3907, 4096)):
    dim = asset["dim"]
    # 生产调用一律带 category（DEFENDER_* / LAND_SIEGE），所以真实池 = 单类条目数，
    # 不是全库条数 —— 这一点直接决定阈值什么时候会被碰到。
    per_cat = {}
    for e in asset["entries"]:
        per_cat[e["category"]] = per_cat.get(e["category"], 0) + 1
    biggest_pool = max(per_cat.values()) if per_cat else 0
    q = asset["entries"][0]["vector"]
    rows = []
    for target in scales:
        pool = grow(asset["entries"], target)
        rows.append({"pool": len(pool), "dim": dim,
                     "mul_adds": scan_mul_adds(len(pool), dim),
                     "ratio": scan_mul_adds(len(pool), dim) / float(THRESHOLDS["embed_mul_adds"]),
                     "scan_ms": measure_scan(pool, q, reps=3 if target <= 1000 else 1)})
    verdict, ratio = judge(biggest_pool, dim)
    return {
        "asset_items": asset["items"], "asset_dim": dim, "corpus_lines": corpus_lines,
        "per_cat": per_cat, "biggest_pool": biggest_pool, "rows": rows,
        "verdict": verdict, "scan_ratio": ratio,
        "bfs_candidates": pseudo_ann_candidate_set(asset["items"], asset["adjacency"])[0],
        "graph_nodes": len(asset["degrees"]),
    }


def parse_doc_thresholds(text):
    """取文档里那行机读阈值：BENCH-THRESHOLDS:k=v;k=v;..."""
    m = re.search(r"BENCH-THRESHOLDS:([0-9A-Za-z_=;.\-]+)", text)
    if not m:
        return None
    out = {}
    for kv in m.group(1).strip(";").split(";"):
        if "=" in kv:
            k, v = kv.split("=", 1)
            out[k] = v
    return out


def check_doc_drift(asset, corpus_lines, doc_text=None):
    """闸门与文档必须同源；不同源就说明这份评估已经开始烂。"""
    problems = []
    if doc_text is None:
        if not os.path.isfile(DOC):
            return ["%s 不存在，但 SlgRagEngine 的类注释正在引用它（注释许诺了一份没人写的文档）"
                    % os.path.basename(DOC)]
        with open(DOC, encoding="utf-8") as fh:
            doc_text = fh.read()
    marks = parse_doc_thresholds(doc_text)
    if marks is None:
        problems.append("文档缺 BENCH-THRESHOLDS 机读行（阈值两处各写一遍必然漂移）")
    else:
        for key, val in THRESHOLDS.items():
            got = marks.get(key)
            if got is None:
                problems.append("文档缺阈值 %s=%s" % (key, val))
                continue
            try:
                if abs(float(got) - float(val)) > 1e-12:
                    problems.append("阈值 %s 文档=%s 代码=%s（不同源）" % (key, got, val))
            except ValueError:
                problems.append("阈值 %s 文档里的值 %r 不是数字" % (key, got))
        if "corpus_entries" in marks and int(marks["corpus_entries"]) != asset["items"]:
            problems.append("文档记的语料条数 %s ↔ 资产真实 %d（语料涨了没人回头核阈值）"
                            % (marks["corpus_entries"], asset["items"]))
    if corpus_lines is not None and corpus_lines != asset["items"]:
        problems.append("语料 %d 行 ↔ 资产 %d 条不一致（索引没跟着语料重建，检索还在读旧库）"
                        % (corpus_lines, asset["items"]))
    if asset["leftover_bytes"] != 0:
        problems.append("容器尾部残留 %d 字节未解析（端侧解析器与生成器口径已漂移）"
                        % asset["leftover_bytes"])
    return problems


def doc_marker():
    """给文档用的一行（写进文档时保持与 THRESHOLDS 完全一致）。"""
    return "BENCH-THRESHOLDS:" + ";".join("%s=%s" % (k, THRESHOLDS[k]) for k in
                                          sorted(THRESHOLDS))


# ---------------------------------------------------------------- 检索通道契约
#
# 这一节管的是"检索到底有没有接上"。P5 期间在这里发现了一个真实缺陷：
# 端侧 `search()` 按 category 等值过滤，而调用侧用的通道名
# （DEFENDER_LAND / SKILL_SYNERGY / TACTICAL_DECREE）在入包的 V2 资产里**一个都不存在**
# （资产实际是 DEFENDER_SAFE/MODERATE/HARD/AVOID + LAND_SIEGE）。
# 后果是每条生产检索都返回空列表 —— 不报错、不降级，只是 RAG 整层空转，
# 而开发机上恰好看不出来（资产加载失败才会回落种子库，种子库用的正是旧名）。
# 所以这里把"通道 ↔ 资产 category ↔ 调用侧查询"三方对账固化成闸门。

RAG_KT = os.path.join(REPO, "client", "app", "src", "main", "java", "com", "stzb",
                      "assistant", "ai", "rag", "SlgRagEngine.kt")


def _strip_kt(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def load_channel_contract(path=RAG_KT):
    """从 SlgRagEngine.kt 抽出三件事：通道对照表、实际查询的通道、种子语料的 category。"""
    plain = _strip_kt(open(path, encoding="utf-8").read())

    # 1) const val CHANNEL_DEFENDER = "DEFENDER_LAND"
    consts = dict(re.findall(r'const val (CHANNEL_\w+)\s*=\s*"([^"]+)"', plain))

    # 2) SEARCH_CHANNELS = mapOf(CHANNEL_X to setOf("A", "B"), ...)
    channels = {}
    idx = plain.find("SEARCH_CHANNELS")
    block = None
    if idx >= 0:
        eq = plain.find("= mapOf(", idx)
        if eq >= 0:
            depth, start = 0, eq + len("= mapOf(") - 1
            i = start
            while i < len(plain):
                if plain[i] == "(":
                    depth += 1
                elif plain[i] == ")":
                    depth -= 1
                    if depth == 0:
                        block = plain[start + 1:i]
                        break
                i += 1
    if block:
        for m in re.finditer(r"(CHANNEL_\w+|\w+)\s+to\s+setOf\(([^)]*)\)", block):
            name = m.group(1)
            key = consts.get(name, name)
            channels[key] = set(re.findall(r'"([^"]+)"', m.group(2)))

    # 3) 调用侧真正查了哪些通道：search(..., category = CHANNEL_X) / category = "LIT"
    queried = set()
    for m in re.finditer(r"category\s*=\s*(CHANNEL_\w+|\"[^\"]+\")", plain):
        tok = m.group(1)
        queried.add(consts[tok] if tok in consts else tok.strip('"'))

    # 4) 种子语料：RagEntry( "SEED-01", "DEFENDER_LAND", ...
    seeds = set(re.findall(r'RagEntry\(\s*"(SEED-[^"]+)"\s*,\s*"([^"]+)"', plain))
    return {
        "consts": consts, "channels": channels, "queried": queried,
        "seed_categories": {c for _sid, c in seeds},
        "seed_ids": {sid for sid, _c in seeds},
    }


def check_channel_contract(asset, contract):
    """三方对账：调用侧通道 / 端侧对照表 / 真实资产 + 种子。

    返回 (errors, warns)：
    * error  = 这条通道**根本检索不到任何东西**或某批语料永远检索不到 → 必须挡住构建；
    * warn   = 通道能工作但只靠内置种子补齐（V2 资产里没有这类语料）：
               这是待补清单，不是缺陷。把它算作失败会让回归长期飘红，
               而长期飘红的唯一后果是没人再看它（本仓库已在别处栽过这个跟头）。
    """
    errors = []
    warns = []
    asset_cats = {e["category"] for e in asset["entries"]}
    chans = contract["channels"]
    if not chans:
        return ["SlgRagEngine.kt 里解析不到 SEARCH_CHANNELS 对照表（闸门自己瞎了，不许当通过）"], []
    if not contract["queried"]:
        return ["解析不到任何 `category = ...` 检索调用（同上）"], []

    for q in sorted(contract["queried"]):
        if q not in chans:
            errors.append("调用侧查的通道 %r 不在 SEARCH_CHANNELS 对照表里 —— "
                          "会被当字面 category 过滤，极可能一条都不命中" % q)
            continue
        cats = chans[q]
        covered_by_asset = sorted(cats & asset_cats)
        seed_cats = sorted(cats & contract["seed_categories"])
        if not covered_by_asset and not seed_cats:
            errors.append("通道 %r（允许 %s）在真实资产与种子库里都是 0 条 —— 检索永久空转"
                          % (q, "/".join(sorted(cats))))
        elif not covered_by_asset:
            warns.append("通道 %r 在 V2 资产里 0 条，仅靠内置种子补齐（category %s）；"
                         "下次重建索引时应把这类语料补进容器，否则它永远只有种子那几条文案"
                         % (q, "/".join(seed_cats)))

    # 反向：资产里存在、却没有任何通道允许的 category = 付了体积却永远不会被检索到
    allowed = set()
    for cats in chans.values():
        allowed |= cats
    dead = sorted(asset_cats - allowed)
    if dead:
        errors.append("资产里的 category %s 不属于任何检索通道 —— 白占体积，永远检索不到"
                      % "/".join(dead))
    dead_seed = sorted(contract["seed_categories"] - allowed)
    if dead_seed:
        errors.append("种子语料的 category %s 不属于任何检索通道（同上）" % "/".join(dead_seed))
    return errors, warns



def selftest():
    """判据与对账逻辑自身的反例自测：它们坏了，这份评估就成了摆设。"""
    fails = []
    total = [0]

    def want(name, cond, detail=""):
        total[0] += 1
        if not cond:
            fails.append("✗ %s 不成立 %s" % (name, detail))

    # 裁决边界：阈值-1 不越线，阈值刚好越线（判据是 >=，不是 >）
    dim = 512
    need_pool = math.ceil(
        THRESHOLDS["scan_ratio_migrate"] * THRESHOLDS["embed_mul_adds"] / dim)
    want("阈值下 STAY", judge(need_pool - 1, dim)[0] == "STAY", "need=%d" % need_pool)
    want("阈值上 MIGRATE", judge(need_pool, dim)[0] == "MIGRATE")
    want("维度翻倍更早越线", judge(need_pool, dim * 2)[0] == "MIGRATE")
    want("截断线必然越线", judge(THRESHOLDS["truncation_guard"], dim)[0] == "MIGRATE")
    want("当前规模 STAY", judge(42, dim)[0] == "STAY")

    # 耗时表只是参考，但仍必须随规模单调（判据若和实现脱节，这里先响）
    small = grow([{"id": "a", "category": "C", "keywords": [], "vector": [0.1] * 64}], 42)
    big = grow([{"id": "a", "category": "C", "keywords": [], "vector": [0.1] * 64}], 2000)
    want("耗时单调", measure_scan(big, [0.1] * 64, reps=1) >
         measure_scan(small, [0.1] * 64, reps=3) * 0.8)

    # 伪 ANN 取证：候选集恒等于全量；越过截断线才丢条目
    want("伪ANN候选=全量", pseudo_ann_candidate_set(42, [[]] * 42)[0] == 42)
    want("小库零截断损失", pseudo_ann_candidate_set(42)[1] == 0)
    cand, dropped = pseudo_ann_candidate_set(5000)
    want("截断可证伪", dropped == 5000 - THRESHOLDS["truncation_guard"]
         and cand <= THRESHOLDS["truncation_guard"] + 1, "cand=%d dropped=%d" % (cand, dropped))

    # 文档对账：每类漂移都要被抓到，同源时不许误报
    good = doc_marker() + ";corpus_entries=7"
    asset7 = {"items": 7, "dim": 512, "leftover_bytes": 0, "adjacency": [], "degrees": []}
    want("同源不误报", check_doc_drift(asset7, 7, good) == [],
         str(check_doc_drift(asset7, 7, good)))
    want("改文档阈值能抓到",
         bool([p for p in check_doc_drift(asset7, 7, good.replace("scan_ratio_migrate=0.1",
                                                                  "scan_ratio_migrate=0.5"))
               if "scan_ratio_migrate" in p]))
    want("删机读行能抓到", bool(check_doc_drift(asset7, 7, "只有散文，没有机读行")))
    want("语料行数漂移能抓到",
         bool([p for p in check_doc_drift(asset7, 9, good) if "不一致" in p]))
    want("文档条数漂移能抓到",
         bool([p for p in check_doc_drift(asset7, 7, good.replace("corpus_entries=7",
                                                                  "corpus_entries=99"))
               if "语料条数" in p]))
    want("尾部残留能抓到",
         bool([p for p in check_doc_drift(dict(asset7, leftover_bytes=3), 7, good)
               if "残留" in p]))

    # 通道契约：先证明解析器不是瞎的，再证明三类缺陷各自被抓到
    real = load_channel_contract()
    want("通道解析非空", bool(real["channels"]) and bool(real["queried"])
         and bool(real["seed_categories"]),
         "channels=%s queried=%s seeds=%s"
         % (sorted(real["channels"]), sorted(real["queried"]), sorted(real["seed_categories"])))
    want("查询通道都在表里", real["queried"] <= set(real["channels"]),
         str(sorted(real["queried"] - set(real["channels"]))))

    asset = {"entries": [{"category": c} for c in ("DEFENDER_SAFE", "LAND_SIEGE")],
             "leftover_bytes": 0}
    c_ok = {"channels": {"DEFENDER_LAND": {"DEFENDER_SAFE", "LAND_SIEGE"},
                         "TACTICAL_DECREE": {"TACTICAL_DECREE"}},
            "queried": {"DEFENDER_LAND"}, "seed_categories": {"TACTICAL_DECREE"},
            "seed_ids": {"SEED-05"}}
    errs, warns = check_channel_contract(asset, c_ok)
    want("正常契约零错误", errs == [], str(errs))
    want("种子补齐只算警告", len(warns) == 0 or all("仅靠内置种子" in w for w in warns),
         str(warns))
    c_dead = dict(c_ok, queried={"DEFENDER_LAND", "HERO_COUNTER"})
    c_dead["channels"] = dict(c_ok["channels"])
    errs, _w = check_channel_contract(asset, c_dead)
    want("未登记通道被抓", bool([e for e in errs if "不在 SEARCH_CHANNELS" in e]), str(errs))
    c_empty = {"channels": {"X": {"NO_SUCH_CAT"}}, "queried": {"X"},
               "seed_categories": set(), "seed_ids": set()}
    errs, _w = check_channel_contract(asset, c_empty)
    want("空通道被抓", bool([e for e in errs if "永久空转" in e]), str(errs))
    c_orphan = {"channels": {"X": {"DEFENDER_SAFE"}}, "queried": {"X"},
                "seed_categories": set(), "seed_ids": set()}
    errs, _w = check_channel_contract(asset, c_orphan)
    want("无人检索的语料被抓", bool([e for e in errs if "永远检索不到" in e]), str(errs))
    want("解析器瞎了必须响",
         bool(check_channel_contract(asset, {"channels": {}, "queried": set(),
                                             "seed_categories": set(), "seed_ids": set()})[0]))

    print("=== tools/rag_bench.py 自测 ===")
    for f in fails:
        print("  " + f)
    print("[%s] 断言 %d 项，失败 %d" % ("OK" if not fails else "FAIL", total[0], len(fails)))
    return 0 if not fails else 1


def main(argv=None):
    # 与 build_rag_index.py / validate_*.py 同口径：本脚本在**报告失败**时才打印 ✗，
    # Windows PowerShell 默认 GBK 编不出它 —— 于是"闸门发现漂移"表现为脚本崩溃而不是
    # 一条可读的失败信息。兜底必须加在 main 入口，覆盖 selftest / contract 各条路径。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="P5 RAG 检索成本实测与迁移阈值评估")
    ap.add_argument("--selftest", action="store_true",
                    help="只跑判据与文档对账的反例自测（不读资产）")
    ap.add_argument("--contract", action="store_true",
                    help="只做检索通道契约 + 文档对账（快、确定性强，适合进回归）")
    ap.add_argument("--doc-marker", action="store_true",
                    help="打印文档里那行机读阈值（避免手抄出错）")
    ap.add_argument("--asset", default=ASSET)
    args = ap.parse_args(argv)
    if args.doc_marker:
        print(doc_marker())
        return 0
    if args.selftest:
        return selftest()

    if not os.path.isfile(args.asset):
        print("❌ 资产缺失：%s" % args.asset)
        return 2
    try:
        asset = load_asset(args.asset)
        lines = corpus_line_count()
    except Exception as e:
        # 实测跑不起来 = 必须响，绝不把"没测到"当成"没问题"
        print("❌ 资产解析异常：%s: %s" % (type(e).__name__, e))
        return 2

    if args.contract:
        contract = load_channel_contract()
        errs, warns = check_channel_contract(asset, contract)
        drift = check_doc_drift(asset, lines, None)
        print("=== 检索通道契约（调用侧 ↔ 对照表 ↔ 真实资产 + 种子）===")
        print("  资产 category：%s" % sorted({e["category"] for e in asset["entries"]}))
        print("  端侧对照表：%s" % {k: sorted(v) for k, v in sorted(contract["channels"].items())})
        print("  调用侧查询：%s" % sorted(contract["queried"]))
        print("  种子语料：%s（%d 条）" % (sorted(contract["seed_categories"]),
                                          len(contract["seed_ids"])))
        for w in warns:
            print("  ⚠️  " + w)
        for e in errs:
            print("  ✗ " + e)
        for p in drift:
            print("  ✗ " + p)
        if errs or drift:
            print("[FAIL] 错误 %d 条、文档对账问题 %d 条" % (len(errs), len(drift)))
            return 2
        print("[OK] 每个检索通道都取得到语料；资产里没有无人检索的死词条"
              "（警告 %d 条）" % len(warns))
        return 0

    measured = assess(asset, lines)

    avg_deg = (sum(asset["degrees"]) / len(asset["degrees"])) if asset["degrees"] else 0.0
    print("=== 资产现状（从真实文件读出，不引用文档）===")
    print("  %s" % os.path.relpath(asset["path"], REPO))
    print("  容器 %s  magic=%s  version=%d  条目=%d  维度=%d"
          % (human(asset["size"]), asset["magic"], asset["version"],
             asset["items"], asset["dim"]))
    print("  向量区 %s（占 %.1f%%）  文本区 %s  图区 %s（%d 节点，平均度 %.1f）  尾部残留 %d 字节"
          % (human(asset["vec_bytes"]), 100.0 * asset["vec_bytes"] / asset["size"],
             human(asset["text_bytes"]), human(asset["graph_bytes"]),
             len(asset["degrees"]), avg_deg, asset["leftover_bytes"]))
    print("  语料源 JSONL 行数=%s" % measured["corpus_lines"])
    print("  分类分布=%s" % measured["per_cat"])
    print("  最大单类候选池=%d 条（生产调用一律带 category，所以这才是真实池）"
          % measured["biggest_pool"])

    print("\n=== 全量精确扫耗时（PC 参考口径，不参与裁决）===")
    print("  " + DEVICE_NOTES)
    print("  %-8s %-6s %-12s %-10s %-10s" % ("池规模", "维度", "乘加次数", "占embedd", "单次扫描"))
    for r in measured["rows"]:
        print("  %-8d %-6d %-12d %-10s %-10s"
              % (r["pool"], r["dim"], r["mul_adds"], "%.2f%%" % (100 * r["ratio"]),
                 "%.3f ms" % r["scan_ms"]))
    print("  一次 bge 查询侧 embedd 的乘加按 %d 量级估算（INT8 约 1 字节/参数，未逐层核验），"
          % THRESHOLDS["embed_mul_adds"])
    print("  这是估算口径，理由见文档第二节；本行不参与裁决，裁决只看它与扫描乘加的比值。")
    print("  当前真实池占它的 %.3f%% —— 图索引要省的时间落在噪声里。"
          % (100 * measured["scan_ratio"]))

    print("\n=== 已被删除的伪 ANN 分支（取证）===")
    print("  图区节点 %d，'从全部节点起 BFS' 的候选数 %d → 候选集恒等于全量扫。"
          % (measured["graph_nodes"], measured["bfs_candidates"]))
    print("  它省不掉任何一次余弦比较，只多跑一趟遍历，还带一个 %d 的截断："
          % THRESHOLDS["truncation_guard"])
    print("  语料超过它，就会按遍历顺序静默丢掉尾部条目（5000 条时丢 %d 条）——不报错、不降级。"
          % (5000 - THRESHOLDS["truncation_guard"]))

    drift = check_doc_drift(asset, measured["corpus_lines"], None)
    contract = load_channel_contract()
    c_errs, c_warns = check_channel_contract(asset, contract)
    print("\n=== 检索通道契约 ===")
    print("  调用侧查询 %s；端侧对照表 %s；资产 category %s"
          % (sorted(contract["queried"]),
             {k: sorted(v) for k, v in sorted(contract["channels"].items())},
             sorted({e["category"] for e in asset["entries"]})))
    for w in c_warns:
        print("  ⚠️  " + w)
    for e in c_errs:
        print("  ✗ " + e)
    if not c_errs:
        print("  [OK] 每个通道都取得到语料（修复前：所有通道都是空池）")

    print("\n=== 文档对账 ===")
    if drift:
        for p in drift:
            print("  ✗", p)
    else:
        print("  [OK] %s 的机读阈值行与真实资产、语料行数全部同源" % os.path.basename(DOC))

    print("\n=== 结论 ===")
    print("  裁决：%s（真实池 %d 条 × %d 维 = 乘加占比 %.3f%%，阈值 %s%%）"
          % (measured["verdict"], measured["biggest_pool"], asset["dim"],
             100 * measured["scan_ratio"], 100 * THRESHOLDS["scan_ratio_migrate"]))
    if measured["verdict"] == "STAY" and not drift and not c_errs:
        print("  维持全量精确扫；sqlite-vec 不引入（代价与理由见文档第四节）")
        return 0
    # 口径漂移 / 通道空转（退出码 2）与"该迁移了"（1）是两回事：
    # 前者说明这条闸门现在的输出不可信，后者才是评估结论。
    if drift or c_errs:
        print("  闸门不可信：文档对账问题 %d 条、通道契约错误 %d 条" % (len(drift), len(c_errs)))
        return 2
    return 1


if __name__ == "__main__":
    sys.exit(main())
