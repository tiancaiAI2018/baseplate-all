CREATE TABLE IF NOT EXISTS rule_set (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    scope           VARCHAR(128) NOT NULL,
    version         VARCHAR(64) NOT NULL,
    match_mode      VARCHAR(8) NOT NULL DEFAULT 'ALL',
    conditions_json JSONB NOT NULL,
    actions_json    JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rule_set UNIQUE (tenant_id, scope, version)
);
CREATE INDEX IF NOT EXISTS idx_rule_set_scope ON rule_set(tenant_id, scope, id DESC);
