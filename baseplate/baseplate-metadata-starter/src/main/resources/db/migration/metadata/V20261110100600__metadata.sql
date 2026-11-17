CREATE TABLE IF NOT EXISTS meta_schema (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    kind           VARCHAR(16) NOT NULL,
    code           VARCHAR(64) NOT NULL,
    name           VARCHAR(128),
    version        INT NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    schema_json    JSONB NOT NULL,
    ui_schema_json JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT uk_meta_schema UNIQUE (tenant_id, kind, code, version)
);
CREATE INDEX IF NOT EXISTS idx_meta_schema_lookup ON meta_schema(tenant_id, kind, code, version DESC);
