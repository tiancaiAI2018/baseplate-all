CREATE TABLE IF NOT EXISTS cend_submission (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    page_code   VARCHAR(64) NOT NULL,
    form_code   VARCHAR(64),
    payload     JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cend_submission_lookup ON cend_submission(tenant_id, page_code, created_at DESC);
