-- 开放接口从「代码里手工登记」改为「注解声明 + 库表事实来源」
-- ① 目录补主体类型与权限要求：这些是安全边界，控制台只读
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS subject VARCHAR(16) NOT NULL DEFAULT 'CLIENT';
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS required_permission VARCHAR(128);
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS required_scope VARCHAR(64);
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'ANNOTATION';
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS handler VARCHAR(255);
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS sandbox_allowed BOOLEAN NOT NULL DEFAULT true;
-- 人工改过名的条目，下次启动同步时不覆盖
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS label_edited BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE open_api ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- ② 应用：令牌获取方式可以多种并存（client_credentials / authorization_code / refresh_token）
ALTER TABLE open_app ADD COLUMN IF NOT EXISTS grant_types VARCHAR(128) NOT NULL DEFAULT 'client_credentials';
-- 授权码流程的回调白名单，多个用换行分隔，精确匹配
ALTER TABLE open_app ADD COLUMN IF NOT EXISTS redirect_uris TEXT;

-- ③ 授权码：一次性、短时效；只存哈希之外的明文 code（本身即凭证，用完即删）
CREATE TABLE IF NOT EXISTS open_auth_code (
    code         VARCHAR(128) PRIMARY KEY,
    app_id       BIGINT       NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(64)  NOT NULL,
    username     VARCHAR(128),
    scopes       VARCHAR(255),
    redirect_uri VARCHAR(512) NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_open_auth_code_app ON open_auth_code(app_id, created_at DESC);

-- ④ 刷新令牌：只存 SHA-256，泄露库也换不出新令牌
CREATE TABLE IF NOT EXISTS open_refresh_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    app_id     BIGINT       NOT NULL,
    tenant_id  VARCHAR(64)  NOT NULL,
    user_id    VARCHAR(64)  NOT NULL,
    username   VARCHAR(128),
    scopes     VARCHAR(255),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_open_refresh_app_user ON open_refresh_token(app_id, user_id);

-- ⑤ 调用日志记下「代表哪个用户」，应用调与应用代用户调要能分开看
ALTER TABLE open_api_log ADD COLUMN IF NOT EXISTS act_user VARCHAR(128);

COMMENT ON COLUMN open_api.subject IS 'CLIENT 机器身份 / USER 用户身份 / ANY 均可';
COMMENT ON COLUMN open_api.source IS 'ANNOTATION 注解扫描 / MANUAL 控制台手工登记';
COMMENT ON COLUMN open_app.grant_types IS '逗号分隔：client_credentials,authorization_code,refresh_token';
