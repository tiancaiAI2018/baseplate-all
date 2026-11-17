-- 流程发起配置：目录展示（分类/图标/描述）+ 发起侧数据权限（谁能发起）
CREATE TABLE IF NOT EXISTS wf_process_setting (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    process_key      VARCHAR(128) NOT NULL,
    category         VARCHAR(64)  NOT NULL DEFAULT '通用',
    description      VARCHAR(512) NOT NULL DEFAULT '',
    icon             VARCHAR(32)  NOT NULL DEFAULT '',
    start_scope_type VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    start_scope_value TEXT        NOT NULL DEFAULT '',
    start_form_code  VARCHAR(128) NOT NULL DEFAULT '',
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order       INT          NOT NULL DEFAULT 0,
    updated_by       VARCHAR(64)  NOT NULL DEFAULT '',
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_process_setting UNIQUE (tenant_id, process_key)
);