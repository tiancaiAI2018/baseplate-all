CREATE TABLE IF NOT EXISTS open_api (
    code        VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    method      VARCHAR(16) NOT NULL,
    path        VARCHAR(255) NOT NULL,
    group_name  VARCHAR(64),
    description VARCHAR(512),
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS open_app_api (
    app_id   BIGINT NOT NULL,
    api_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (app_id, api_code)
);

ALTER TABLE open_api_log ADD COLUMN IF NOT EXISTS app_name VARCHAR(128);
ALTER TABLE open_api_log ADD COLUMN IF NOT EXISTS api_code VARCHAR(64);
ALTER TABLE open_api_log ADD COLUMN IF NOT EXISTS user_agent VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_open_api_log_app_time ON open_api_log(app_key, created_at DESC);
