-- identity 管理面补充：内置角色标记与索引
ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS built_in BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_sys_user_tenant_status ON sys_user(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_sys_user_org ON sys_user(tenant_id, org_id);
CREATE INDEX IF NOT EXISTS idx_sys_user_role_user ON sys_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_time ON sys_login_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sys_password_history_user ON sys_password_history(user_id, created_at DESC);
