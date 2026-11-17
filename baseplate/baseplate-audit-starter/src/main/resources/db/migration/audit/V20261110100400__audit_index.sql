CREATE INDEX IF NOT EXISTS idx_aud_access_tenant ON aud_access_log(tenant_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_aud_business_tenant ON aud_business_log(tenant_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_aud_access_actor ON aud_access_log(actor);
