-- 工作流操作流水：引擎历史表只记录「发生了什么」，不记录「谁因为什么做的」。
-- 页面的「驳回原因 / 打回次数 / 作废与迁移的操作人」都从这张表聚合。
CREATE TABLE IF NOT EXISTS wf_operation_log (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL DEFAULT 'default',
    process_instance_id   VARCHAR(64),
    process_definition_id VARCHAR(64),
    task_id               VARCHAR(64),
    activity_id           VARCHAR(128),
    action                VARCHAR(24)  NOT NULL,
    decision              VARCHAR(24),
    reason                TEXT,
    detail                TEXT,
    operator              VARCHAR(128),
    target_node_id        VARCHAR(128),
    occurred_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wf_op_instance ON wf_operation_log (process_instance_id, id);
CREATE INDEX IF NOT EXISTS idx_wf_op_definition ON wf_operation_log (process_definition_id, id);
CREATE INDEX IF NOT EXISTS idx_wf_op_occurred ON wf_operation_log (occurred_at DESC);
