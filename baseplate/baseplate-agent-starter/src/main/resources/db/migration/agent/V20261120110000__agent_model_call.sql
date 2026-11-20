-- 模型调用观测：每次发给模型的 HTTP 请求体（OpenAI 风格）落一行。
-- 这是「实际发送给模型接口」的完整请求：messages 含 skill 注入后的 system 消息，
-- tools 含全部可用工具（自定义 + skill 自带），导出微调样本时直接原样输出。
CREATE TABLE IF NOT EXISTS agent_model_call (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    session_id  BIGINT      NOT NULL REFERENCES agent_session (id) ON DELETE CASCADE,
    -- 本轮对话内的第几次模型调用（ReAct 多步时 >1）
    step        INT         NOT NULL DEFAULT 0,
    model       VARCHAR(128),
    request     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_model_call_session
    ON agent_model_call (session_id, id DESC);
