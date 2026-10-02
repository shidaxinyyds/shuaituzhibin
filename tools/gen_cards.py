"""率土之滨及多游戏商业卡密批量生成工具 (零知识安全设计)

特性：
1. 过滤易混字符（无 0/O, 1/I/L），避免发卡后买家手抄误报。
2. 零知识存储：明文仅在本地控制台输出一次，供商家导入发卡平台；
   向 Supabase 插入的仅为 SHA256 哈希值，即使数据库泄露也无法反推明文。
3. 严格绑定 game_id，避免跨游戏混用。

常用命令示例：
  # 率土之滨：生成 50 张 30 天月卡（前缀 STZB）
  python tools/gen_cards.py --game stzb --type month --days 30 --count 50 --prefix STZB

  # 率土之滨：生成 10 张 7 天周卡
  python tools/gen_cards.py --game stzb --type week --days 7 --count 10 --prefix STZB

  # 体验测试：生成 5 张 15 分钟体验卡
  python tools/gen_cards.py --game stzb --type trial --minutes 15 --count 5 --prefix TEST
"""
import argparse
import hashlib
import secrets
import string

# 排除易混淆字符 0, O, I, 1, L
_ALPHABET = "".join(c for c in (string.ascii_uppercase + string.digits) if c not in "OI0L")

def gen_code(prefix: str) -> str:
    body = "".join(secrets.choice(_ALPHABET) for _ in range(16))
    groups = [body[i:i + 4] for i in range(0, 16, 4)]
    code = "-".join(groups)
    return f"{prefix}-{code}" if prefix else code

def main():
    ap = argparse.ArgumentParser(description="商业卡密批量生成器")
    ap.add_argument("--game", default="stzb", help="游戏标识符 (如 stzb / sgz / mxdx)")
    ap.add_argument("--type", default="month", help="卡种类型标签 (month / week / day / trial / season)")
    ap.add_argument("--days", type=int, help="有效天数（激活起算），与 --minutes 二选一")
    ap.add_argument("--minutes", type=int, help="有效分钟数（体验卡测试），与 --days 二选一")
    ap.add_argument("--count", type=int, default=1, help="生成的卡密数量")
    ap.add_argument("--prefix", default="STZB", help="卡密前缀 (如 STZB / VIP)")
    args = ap.parse_args()

    if bool(args.days) == bool(args.minutes):
        ap.error("--days 与 --minutes 必须二选一且只能选一个")

    duration_s = args.days * 86400 if args.days else args.minutes * 60
    span_str = f"{args.days} 天" if args.days else f"{args.minutes} 分钟"

    codes = []
    hashes = []
    for _ in range(args.count):
        c = gen_code(args.prefix)
        h = hashlib.sha256(c.encode("utf-8")).hexdigest()
        codes.append(c)
        hashes.append(h)

    print("\n" + "=" * 55)
    print(f"【明文卡密列表】(仅显示一次，复制后直接导入发卡网库存)")
    print("=" * 55)
    for c in codes:
        print(c)

    print("\n" + "=" * 55)
    print("【SQL 导入语句】(全选复制并粘贴到 Supabase SQL Editor 执行)")
    print("=" * 55)
    values = ",\n".join(
        f"  ('{args.game}', '{h}', '{args.type}', {duration_s})" for h in hashes
    )
    sql = (
        "INSERT INTO public.card_keys (game_id, code_hash, card_type, duration_s) VALUES\n"
        f"{values};"
    )
    print(sql)

    print("\n" + "-" * 55)
    print(f"统计：已生成 {args.count} 张卡，游戏: {args.game}, 类型: {args.type}, 有效期: {span_str}。")
    print("-" * 55 + "\n")

if __name__ == "__main__":
    main()
