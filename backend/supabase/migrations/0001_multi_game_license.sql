-- ============================================================================
-- 率土之滨 / 多游戏商业卡密授权底座 · Supabase (PostgreSQL) 建表与原子存储过程
-- 适用范围：率土之滨 (stzb) 及后续 6 款游戏多业务隔离
-- 部署方法：Supabase 网页控制台 -> SQL Editor -> 全选粘贴执行
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------------------------
-- 1. 卡密库存表 (card_keys)
-- 仅存储卡密的 SHA256 哈希值，绝对不存明文，即使数据库泄露也无法反推明文卡密。
-- 引入 game_id 区分不同游戏，避免不同游戏间的卡密混用。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.card_keys (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  game_id      TEXT        NOT NULL DEFAULT 'stzb',
  code_hash    TEXT        NOT NULL UNIQUE,
  card_type    TEXT        NOT NULL DEFAULT 'month',  -- trial / day / week / month / permanent
  duration_s   BIGINT      NOT NULL,                  -- 有效期（秒）
  status       TEXT        NOT NULL DEFAULT 'unused', -- unused / used / revoked
  bound_device TEXT,                                  -- 绑定的硬件指纹 SHA256
  activated_at TIMESTAMPTZ,
  note         TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_card_keys_lookup ON public.card_keys (game_id, code_hash, status);

-- ----------------------------------------------------------------------------
-- 2. 设备授权生效表 (licenses)
-- 复合主键/唯一索引：(device_id, game_id)。
-- 允许同一台手机同时拥有《率土之滨》、《三国志战略版》等不同游戏的独立授权，互不冲突。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.licenses (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id     TEXT        NOT NULL,
  game_id       TEXT        NOT NULL DEFAULT 'stzb',
  code_hash     TEXT        NOT NULL,
  card_type     TEXT        NOT NULL,
  activated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL,
  last_renew_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked       BOOLEAN     NOT NULL DEFAULT false,
  CONSTRAINT uk_device_game UNIQUE (device_id, game_id)
);

CREATE INDEX IF NOT EXISTS idx_licenses_check ON public.licenses (device_id, game_id, revoked, expires_at);

-- 启用行级安全策略 (RLS)，完全禁止匿名客户端直读数据表
ALTER TABLE public.card_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.licenses ENABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- 3. 原子激活函数 (sp_activate)
-- 机制：
--   1) 行级锁 (FOR UPDATE SKIP LOCKED) 杜绝高并发“一卡多激活”撞库。
--   2) 检查设备当前游戏是否已有未到期授权：若存在，支持自动延长时间（续费模式）。
--   3) 状态置为 used 并记录绑定设备。
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sp_activate(p_game_id TEXT, p_code TEXT, p_device TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  v_hash     TEXT := encode(digest(trim(p_code), 'sha256'), 'hex');
  v_card     RECORD;
  v_lic      RECORD;
  v_now      TIMESTAMPTZ := now();
  v_new_exp  TIMESTAMPTZ;
BEGIN
  IF p_device IS NULL OR length(trim(p_device)) = 0 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'no_device');
  END IF;

  IF p_game_id IS NULL OR length(trim(p_game_id)) = 0 THEN
    RETURN jsonb_build_object('ok', false, 'error', 'no_game_id');
  END IF;

  -- 1. 原子查询并锁定未使用的卡密行（并发抢同一张卡只有一人能锁定成功）
  SELECT id, duration_s, card_type INTO v_card
    FROM public.card_keys
   WHERE code_hash = v_hash 
     AND game_id = p_game_id 
     AND status = 'unused'
   FOR UPDATE SKIP LOCKED
   LIMIT 1;

  IF v_card.id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'invalid_code');
  END IF;

  -- 2. 检查当前设备在对应游戏上的现有授权记录
  SELECT id, expires_at, revoked INTO v_lic
    FROM public.licenses
   WHERE device_id = p_device AND game_id = p_game_id
   FOR UPDATE
   LIMIT 1;

  IF v_lic.id IS NOT NULL AND v_lic.revoked = true THEN
    RETURN jsonb_build_object('ok', false, 'error', 'device_revoked');
  END IF;

  -- 3. 计算新的到期时间：若已有有效授权，在原到期时间上累加；否则从当前时间起算
  IF v_lic.id IS NOT NULL AND v_lic.expires_at > v_now THEN
    v_new_exp := v_lic.expires_at + make_interval(secs => v_card.duration_s::int);
  ELSE
    v_new_exp := v_now + make_interval(secs => v_card.duration_s::int);
  END IF;

  -- 4. 核销卡密状态
  UPDATE public.card_keys
     SET status = 'used',
         bound_device = p_device,
         activated_at = v_now
   WHERE id = v_card.id;

  -- 5. 写入/更新授权表
  INSERT INTO public.licenses (device_id, game_id, code_hash, card_type, activated_at, expires_at, last_renew_at, revoked)
  VALUES (p_device, p_game_id, v_hash, v_card.card_type, v_now, v_new_exp, v_now, false)
  ON CONFLICT (device_id, game_id) DO UPDATE
    SET code_hash     = EXCLUDED.code_hash,
        card_type     = EXCLUDED.card_type,
        expires_at    = EXCLUDED.expires_at,
        last_renew_at = v_now,
        revoked       = false;

  RETURN jsonb_build_object(
    'ok', true,
    'game_id', p_game_id,
    'card_type', v_card.card_type,
    'expires_at', extract(epoch from v_new_exp)::bigint,
    'server_now', extract(epoch from v_now)::bigint
  );
END;
$$;

-- ----------------------------------------------------------------------------
-- 4. 续签核验函数 (sp_renew)
-- 客户端凭证到期时后台静默续签；若设备被拉黑或授权过期，立即回绝。
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.sp_renew(p_game_id TEXT, p_device TEXT, p_token_s BIGINT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  v_lic RECORD;
  v_now TIMESTAMPTZ := now();
  v_tok TIMESTAMPTZ;
BEGIN
  SELECT expires_at, revoked, card_type INTO v_lic
    FROM public.licenses
   WHERE device_id = p_device AND game_id = p_game_id
   LIMIT 1;

  IF v_lic.card_type IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'not_found');
  END IF;

  IF v_lic.revoked THEN
    RETURN jsonb_build_object('ok', false, 'error', 'revoked');
  END IF;

  IF v_lic.expires_at <= v_now THEN
    RETURN jsonb_build_object('ok', false, 'error', 'license_expired',
                              'expires_at', extract(epoch from v_lic.expires_at)::bigint);
  END IF;

  -- 短期凭证有效期取 min(现在 + p_token_s, 总到期时间)
  v_tok := LEAST(v_now + make_interval(secs => p_token_s::int), v_lic.expires_at);

  UPDATE public.licenses 
     SET last_renew_at = v_now 
   WHERE device_id = p_device AND game_id = p_game_id;

  RETURN jsonb_build_object(
    'ok', true,
    'game_id', p_game_id,
    'expires_at', extract(epoch from v_lic.expires_at)::bigint,
    'token_exp',  extract(epoch from v_tok)::bigint,
    'server_now', extract(epoch from v_now)::bigint
  );
END;
$$;

-- 权限收紧：移除公共角色的执行权，显式授给 service_role
REVOKE EXECUTE ON FUNCTION public.sp_activate(TEXT, TEXT, TEXT) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.sp_renew(TEXT, TEXT, BIGINT)  FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.sp_activate(TEXT, TEXT, TEXT) TO service_role;
GRANT  EXECUTE ON FUNCTION public.sp_renew(TEXT, TEXT, BIGINT)  TO service_role;

-- ----------------------------------------------------------------------------
-- 5. 跨游戏特征知识库与云端热更表 (game_profiles)
-- 允许管理员在后台直接发布或热更新各游戏的守军天梯、按键别名与战术数值，
-- 客户端通过 Edge Function 自动静默拉取热更新，免去重新发版 APK。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.game_profiles (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  game_id      TEXT        NOT NULL UNIQUE,
  game_name    TEXT        NOT NULL,
  version      TEXT        NOT NULL,
  profile_json JSONB       NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.game_profiles ENABLE ROW LEVEL SECURITY;
GRANT ALL ON TABLE public.game_profiles TO service_role;
