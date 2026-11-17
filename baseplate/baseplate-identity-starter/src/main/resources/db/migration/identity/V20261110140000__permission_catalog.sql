-- 权限目录：模块清单声明为「初始数据」，落库后成为唯一事实来源。
-- 这样新增模块只需在 jar 里声明 permissions，启动时自动 upsert 进这张表；
-- 管理员也能在页面上改中文名、加自定义权限点、停用废弃权限点。
CREATE TABLE IF NOT EXISTS sys_module (
    module_id    VARCHAR(64) PRIMARY KEY,
    label        VARCHAR(128) NOT NULL,
    version      VARCHAR(32),
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    label_edited BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sys_permission (
    id           BIGSERIAL PRIMARY KEY,
    module_id    VARCHAR(64) NOT NULL,
    code         VARCHAR(128) NOT NULL,
    label        VARCHAR(128) NOT NULL,
    type         VARCHAR(16) NOT NULL DEFAULT 'BUTTON',
    source       VARCHAR(16) NOT NULL DEFAULT 'MANIFEST',
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_no      INT NOT NULL DEFAULT 0,
    label_edited BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_sys_permission_code UNIQUE (code)
);

CREATE INDEX IF NOT EXISTS idx_sys_permission_module ON sys_permission(module_id, sort_no, id);
