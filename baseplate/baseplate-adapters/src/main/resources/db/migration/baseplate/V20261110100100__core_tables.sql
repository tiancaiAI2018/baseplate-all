-- 基盘核心表：身份 / 审计 / 开放平台 / Outbox
-- 所有业务表带 tenant_id；公共字段由 MetaObjectHandler 填充（不算审计）

CREATE TABLE IF NOT EXISTS sys_org (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    parent_id   BIGINT,
    name        VARCHAR(128) NOT NULL,
    path        VARCHAR(512) NOT NULL,
    sort_no     INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sys_org_tenant ON sys_org(tenant_id, parent_id);

CREATE TABLE IF NOT EXISTS sys_user (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64) NOT NULL,
    username              VARCHAR(64) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    display_name          VARCHAR(64),
    org_id                BIGINT,
    status                VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    must_change_password  BOOLEAN NOT NULL DEFAULT FALSE,
    password_changed_at   TIMESTAMPTZ,
    failed_attempts       INT NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    ext_json              JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_sys_user UNIQUE (tenant_id, username)
);

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    role_code   VARCHAR(64) NOT NULL,
    role_name   VARCHAR(128) NOT NULL,
    data_scope  VARCHAR(32) NOT NULL DEFAULT 'SELF',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_sys_role UNIQUE (tenant_id, role_code)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id  BIGINT NOT NULL,
    role_id  BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id    BIGINT NOT NULL,
    perm_code  VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_id, perm_code)
);

CREATE TABLE IF NOT EXISTS sys_menu (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64),
    module_id   VARCHAR(64) NOT NULL,
    path        VARCHAR(255) NOT NULL,
    label       VARCHAR(128) NOT NULL,
    perm_code   VARCHAR(128),
    sort_no     INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_password_history (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pwd_history_user ON sys_password_history(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS sys_login_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64),
    username    VARCHAR(64),
    success     BOOLEAN NOT NULL,
    client_ip   VARCHAR(64),
    reason      VARCHAR(128),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS aud_access_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64),
    trace_id    VARCHAR(64),
    actor       VARCHAR(64),
    action      VARCHAR(128),
    resource    VARCHAR(255),
    result_code VARCHAR(64),
    detail      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_aud_access_time ON aud_access_log(created_at DESC);

CREATE TABLE IF NOT EXISTS aud_business_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64),
    trace_id    VARCHAR(64),
    actor       VARCHAR(64),
    action      VARCHAR(128),
    resource    VARCHAR(255),
    before_json JSONB,
    after_json  JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_aud_business_time ON aud_business_log(created_at DESC);

CREATE TABLE IF NOT EXISTS open_app (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    app_key            VARCHAR(64) NOT NULL,
    app_name           VARCHAR(128) NOT NULL,
    client_secret_enc  VARCHAR(512) NOT NULL,
    signing_secret_enc VARCHAR(512) NOT NULL,
    auth_mode          VARCHAR(32) NOT NULL DEFAULT 'OAUTH2_HMAC',
    scopes             VARCHAR(512),
    ip_whitelist       VARCHAR(512),
    qps_limit          INT NOT NULL DEFAULT 10,
    day_limit          INT NOT NULL DEFAULT 10000,
    status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_open_app UNIQUE (app_key)
);

CREATE TABLE IF NOT EXISTS bp_outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64),
    aggregate_id  VARCHAR(128),
    event_type    VARCHAR(128) NOT NULL,
    payload       JSONB NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_status ON bp_outbox_event(status, created_at);
