-- P2：HITL 审批与外部工具调用。
-- HITL：模型想调用敏感工具时暂停，等待用户在页面上同意/拒绝；审批与结果都可跨刷新恢复。

CREATE TABLE IF NOT EXISTS agent_hitl_request (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    session_id          BIGINT      NOT NULL REFERENCES agent_session (id) ON DELETE CASCADE,
    user_id             VARCHAR(64) NOT NULL,
    agent_id            BIGINT      NOT NULL REFERENCES agent_definition (id),
    reply_id            VARCHAR(128),
    -- [{callId, tool, args}]，恢复时用来重建 ConfirmResult
    tool_calls          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    comment             TEXT,
    decided_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    decided_at          TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agent_hitl_session
    ON agent_hitl_request (session_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_hitl_owner
    ON agent_hitl_request (tenant_id, user_id, status);

-- 外部工具：服务端只发调用参数，由前端执行后回传结果。
CREATE TABLE IF NOT EXISTS agent_external_call (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    session_id          BIGINT      NOT NULL REFERENCES agent_session (id) ON DELETE CASCADE,
    user_id             VARCHAR(64) NOT NULL,
    agent_id            BIGINT      NOT NULL,
    reply_id            VARCHAR(128),
    call_id             VARCHAR(128) NOT NULL,
    tool_code           VARCHAR(128) NOT NULL,
    args                JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result              JSONB,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uk_agent_external_call UNIQUE (session_id, call_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_external_call_pending
    ON agent_external_call (session_id, status);
