-- 工作流平台化：节点配置、表单数据、组件素材库；并把存量流程数据回填到 default 租户。
-- 设计约定：用户只在页面做结构化下拉配置，服务端编译成 Flowable 可执行的 BPMN，
-- 绝不把用户输入的表达式/脚本写进引擎。

CREATE TABLE IF NOT EXISTS wf_node_config (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    process_key         VARCHAR(128) NOT NULL,
    definition_version  INT          NOT NULL DEFAULT 0,   -- 0 表示草稿（尚未部署），部署后写入实际版本号
    node_id             VARCHAR(128) NOT NULL,
    node_name           VARCHAR(128),
    node_type           VARCHAR(48),
    assignee_type       VARCHAR(32)  NOT NULL DEFAULT 'INITIATOR',
    assignee_value      TEXT,
    sign_type           VARCHAR(24)  NOT NULL DEFAULT 'SINGLE',
    sign_ratio          INT          NOT NULL DEFAULT 100,
    form_code           VARCHAR(64),
    field_policy        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    decision_config     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    trigger_config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    condition_config    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    timeout_minutes     INT          NOT NULL DEFAULT 0,
    sort_no             INT          NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_node_config UNIQUE (tenant_id, process_key, definition_version, node_id)
);
CREATE INDEX IF NOT EXISTS idx_wf_node_config_lookup
    ON wf_node_config (tenant_id, process_key, definition_version);

-- 表单数据：按实例 + 节点存一份，办理时可回显、可审计，不依赖流程变量是否开启 history
CREATE TABLE IF NOT EXISTS wf_form_data (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    instance_id   VARCHAR(64)  NOT NULL,
    node_id       VARCHAR(128) NOT NULL,
    form_code     VARCHAR(64)  NOT NULL,
    data_json     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_by    VARCHAR(128),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_form_data UNIQUE (tenant_id, instance_id, node_id, form_code)
);
CREATE INDEX IF NOT EXISTS idx_wf_form_data_instance ON wf_form_data (tenant_id, instance_id);

-- 组件素材库：自定义组件片段，注册进设计器左侧，也能被别的页面单独渲染复用
CREATE TABLE IF NOT EXISTS wf_widget (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    category     VARCHAR(32)  NOT NULL DEFAULT 'BASIC',
    rule_json    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    description  TEXT,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    version      INT          NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_widget UNIQUE (tenant_id, code, version)
);

-- 存量：Flowable 用空字符串表示「无租户」，之前所有部署/查询都没带 tenantId。
-- 统一回填 default，之后新部署一律带真实 tenantId；跨租户查询靠引擎的 tenant 条件隔离。
DO $$
DECLARE
    target text;
BEGIN
    FOR target IN
        SELECT table_name FROM information_schema.columns
        WHERE table_schema = 'public' AND column_name = 'tenant_id_'
          AND table_name LIKE 'act\_%' ESCAPE '\'
    LOOP
        EXECUTE format(
            'UPDATE %I SET tenant_id_ = ''default'' WHERE tenant_id_ IS NULL OR tenant_id_ = ''''',
            target);
    END LOOP;
END $$;
