-- 开放平台：沙盒开关、密钥轮换时间、调用日志
ALTER TABLE open_app ADD COLUMN IF NOT EXISTS sandbox_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE open_app ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS open_api_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64),
    app_key     VARCHAR(64),
    sandbox     BOOLEAN NOT NULL DEFAULT FALSE,
    trace_id    VARCHAR(64),
    method      VARCHAR(16),
    path        VARCHAR(512),
    status      INT,
    result_code VARCHAR(64),
    latency_ms  INT,
    client_ip   VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_open_api_log_app ON open_api_log(app_key, id DESC);
CREATE INDEX IF NOT EXISTS idx_open_api_log_time ON open_api_log(created_at DESC);
