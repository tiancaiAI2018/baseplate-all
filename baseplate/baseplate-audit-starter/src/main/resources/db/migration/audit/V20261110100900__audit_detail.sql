ALTER TABLE aud_access_log ADD COLUMN IF NOT EXISTS client_ip VARCHAR(64);
ALTER TABLE aud_access_log ADD COLUMN IF NOT EXISTS user_agent VARCHAR(512);
ALTER TABLE aud_access_log ADD COLUMN IF NOT EXISTS duration_ms INT;
ALTER TABLE aud_access_log ADD COLUMN IF NOT EXISTS request_query VARCHAR(1024);

CREATE INDEX IF NOT EXISTS idx_aud_access_actor ON aud_access_log(tenant_id, actor, id DESC);
CREATE INDEX IF NOT EXISTS idx_aud_access_status ON aud_access_log(tenant_id, result_code, id DESC);
CREATE INDEX IF NOT EXISTS idx_aud_business_action ON aud_business_log(tenant_id, action, id DESC);
