"""跨游戏特征知识库云端热更发布工具 (upload_profile.py)

作用：
  读取本地游戏知识库 JSON 文件（如 pipeline/rate_of_land.json），
  自动生成 Supabase PostgreSQL 的 upsert 导入语句。
  在 Supabase SQL Editor 执行后，全网手机端辅助即刻静默热更新生效！无需重新打包发版 APK！

用法示例：
  python tools/upload_profile.py --json pipeline/rate_of_land.json
"""

import argparse
import json
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

def main():
    parser = argparse.ArgumentParser(description="跨游戏知识库热更发布工具")
    parser.add_argument("--json", required=True, help="知识库 JSON 文件路径 (如 pipeline/rate_of_land.json)")
    args = parser.parse_args()

    if not os.path.exists(args.json):
        print(f"❌ 错误：指定的文件不存在: {args.json}")
        sys.exit(1)

    with open(args.json, "r", encoding="utf-8") as f:
        try:
            data = json.load(f)
        except Exception as e:
            print(f"❌ 错误：JSON 文件解析失败: {e}")
            sys.exit(1)

    game_id = data.get("game_id", "stzb")
    game_name = data.get("game_name", "率土之滨")
    version = data.get("profile_version", "1.0.0")

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

if __name__ == "__main__":
    main()
