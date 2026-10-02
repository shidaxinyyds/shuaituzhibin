// ============================================================================
// 率土之滨 / 多游戏商业卡密授权 · Supabase Edge Function
// 部署命令：supabase functions deploy license --no-verify-jwt
// 所需环境变量 (Dashboard -> Project Settings -> Edge Functions -> Secrets)：
//   LICENSE_TOKEN_SECRET : 64位十六进制密钥（与客户端 C++ 静态密钥一致）
//   SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY : Supabase 原生自动注入
// ============================================================================
// deno-lint-ignore-file no-explicit-any

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const TOKEN_SECRET = Deno.env.get("LICENSE_TOKEN_SECRET") || "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

// 单次签发的本地凭证有效期（秒）：默认 3 天。到期后客户端后台静默触发 renew。
// 总时长由 PostgreSQL 内的 expires_at 绝对封顶，防篡改。
const TOKEN_S = 3 * 24 * 3600;

const enc = new TextEncoder();

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.trim();
  const out = new Uint8Array(clean.length >> 1);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(clean.substring(i * 2, i * 2 + 2), 16);
  }
  return out;
}

function b64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// 规范签名串：device_id | game_id | code | expires_at | token_exp
function canonical(device: string, gameId: string, code: string, expiresAt: number, tokenExp: number): string {
  return `${device}\u00a6${gameId}\u00a6${code}\u00a6${expiresAt}\u00a6${tokenExp}`;
}

async function signPayload(canon: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    hexToBytes(TOKEN_SECRET),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(canon));
  return b64url(sig);
}

async function rpc(fn: string, params: Record<string, any>): Promise<any> {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      apikey: SERVICE_KEY,
      Authorization: `Bearer ${SERVICE_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(params),
  });
  return await res.json();
}

// 极轻量数据库探测：打到真实的 Postgres 实例上，刷新 Supabase 免费版活跃度
async function touchDb(): Promise<number> {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/licenses?select=id&limit=1`,
    { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` } },
  );
  await res.arrayBuffer();
  return res.status;
}

// 查询特定设备的单游戏授权状态
async function heartbeatDevice(device: string, gameId: string) {
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/licenses?select=expires_at,revoked&device_id=eq.${encodeURIComponent(device)}&game_id=eq.${encodeURIComponent(gameId)}&limit=1`,
    { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` } },
  );
  const rows = await res.json().catch(() => [] as any[]);
  const now = Math.floor(Date.now() / 1000);
  if (!Array.isArray(rows) || rows.length === 0) {
    return { valid: false, revoked: false, expiresAt: null, error: "not_found" };
  }
  const row = rows[0];
  const revoked = row.revoked === true;
  const expiresAt = row.expires_at ? Math.floor(Date.parse(row.expires_at) / 1000) : null;
  const valid = !revoked && expiresAt !== null && expiresAt > now;
  return { valid, revoked, expiresAt };
}

async function handle(req: Request): Promise<Response> {
  const json = await req.json().catch(() => ({} as any));
  const action = String(json.action ?? "").trim();
  const device = String(json.device_id ?? "").trim();
  const gameId = String(json.game_id ?? "stzb").trim();
  const code = String(json.code ?? "").trim();

  let out: any;
  const now = Math.floor(Date.now() / 1000);

  if (action === "heartbeat") {
    if (device) {
      const hb = await heartbeatDevice(device, gameId);
      return Response.json({
        ok: true,
        game_id: gameId,
        valid: hb.valid,
        revoked: hb.revoked,
        expires_at: hb.expiresAt,
        server_time: now,
        error: hb.error ?? null,
      });
    }
    // 定时保活调用（无设备参数时）：仅用于产生数据库读写，防止 7 天暂停
    const status = await touchDb();
    return Response.json({ ok: true, db: status, server_time: now });
  } else if (action === "get_profile") {
    const currentVer = String(json.current_version ?? "").trim();
    const res = await fetch(
      `${SUPABASE_URL}/rest/v1/game_profiles?select=profile_json,version&game_id=eq.${encodeURIComponent(gameId)}&limit=1`,
      { headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` } }
    ).catch(() => null);

    const rows = res && res.ok ? await res.json().catch(() => []) : [];
    if (Array.isArray(rows) && rows.length > 0) {
      const row = rows[0];
      const remoteVer = String(row.version ?? "");
      const hasNew = remoteVer > currentVer;
      return Response.json({
        ok: true,
        game_id: gameId,
        has_new_version: hasNew,
        latest_version: remoteVer,
        profile_json: hasNew ? row.profile_json : undefined,
      });
    }
    return Response.json({
      ok: true,
      game_id: gameId,
      has_new_version: false,
      latest_version: currentVer || "2026.10.1",
    });
  } else if (action === "activate") {
    if (!device) return Response.json({ ok: false, error: "no_device" });
    if (!code) return Response.json({ ok: false, error: "no_code" });
    out = await rpc("sp_activate", { p_game_id: gameId, p_code: code, p_device: device });
  } else if (action === "renew") {
    if (!device) return Response.json({ ok: false, error: "no_device" });
    out = await rpc("sp_renew", { p_game_id: gameId, p_device: device, p_token_s: TOKEN_S });
  } else {
    return Response.json({ ok: false, error: "bad_action" });
  }

  if (!out || out.ok !== true) {
    return Response.json({ ok: false, error: out?.error ?? "server_error" });
  }

  const expiresAt = Number(out.expires_at);
  const tokenExp = action === "activate"
    ? Math.min(now + TOKEN_S, expiresAt)
    : Number(out.token_exp);

  const canon = canonical(device, gameId, code || "renew", expiresAt, tokenExp);
  const sig = await signPayload(canon);

  // 格式：device_id | game_id | code | expires_at | token_exp | signature
  const token = `${device}|${gameId}|${code || "renew"}|${expiresAt}|${tokenExp}|${sig}`;

  return Response.json({
    ok: true,
    game_id: gameId,
    token,
    expires_at: expiresAt,
    token_exp: tokenExp,
    card_type: out.card_type ?? null,
    server_time: now,
  });
}

Deno.serve((req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type",
      },
    });
  }
  if (req.method !== "POST") {
    return Response.json({ ok: false, error: "method_not_allowed" });
  }
  return handle(req);
});
