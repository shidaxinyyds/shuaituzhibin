"""Supabase 免费项目保活脚本 (防止 7 天无访问自动暂停)

机制：
向 Edge Function 发送携带 { "action": "heartbeat" } 的 POST 请求。
Edge Function 内部会执行 SELECT id FROM licenses LIMIT 1，产生实际的数据库 I/O 活动，
从而重置 Supabase 的 7 天暂停计时器。

推荐运行方式：
1. 放入 GitHub Actions，配置 cron 定时每周一/周四自动触发（完全免费）。
2. 或在 Windows 本机任务计划程序中设置每周定时运行。

环境变量（或直接配置参数）：
  SUPABASE_FUNCTION_URL: 你的 Edge Function 地址，例如：
  https://xxxx.supabase.co/functions/v1/license
"""
import os
import sys
import json
import urllib.request

def ping_supabase(function_url: str):
    if not function_url or "xxxx" in function_url:
        print("[警告] 请在脚本中或环境变量中提供有效的 SUPABASE_FUNCTION_URL")
        return False

    payload = json.dumps({"action": "heartbeat"}).encode("utf-8")
    req = urllib.request.Request(
        function_url,
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"[成功] 心跳请求已送达，响应状态: {data}")
            return True
    except Exception as e:
        print(f"[错误] 心跳请求失败: {e}")
        return False

if __name__ == "__main__":
    url = os.environ.get("SUPABASE_FUNCTION_URL", "")
    if len(sys.argv) > 1:
        url = sys.argv[1]
    ping_supabase(url)
