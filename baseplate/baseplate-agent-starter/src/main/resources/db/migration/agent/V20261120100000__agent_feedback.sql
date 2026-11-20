-- 消息评分：用户对助手回复点「有用 / 没用」（USEFUL / NOT_USEFUL）。
-- 观测页按此聚合质量信号，后续调优 / 微调时用来筛选样本。
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS feedback VARCHAR(16);
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS feedback_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_message_feedback
    ON agent_message (tenant_id, feedback) WHERE feedback IS NOT NULL;
