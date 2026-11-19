-- AI 生成的工作流草稿：先落草稿，用户在设计器确认后再部署。
-- 草稿只属于当前租户；bpmn_xml 由服务端按结构化 spec 编译，前端直接 import 到画布。

CREATE TABLE IF NOT EXISTS wf_process_draft (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    process_key         VARCHAR(128) NOT NULL,
    name                VARCHAR(200),
    description         TEXT,
    spec                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    bpmn_xml            TEXT         NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_by          VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wf_process_draft_tenant
    ON wf_process_draft (tenant_id, created_at DESC);
