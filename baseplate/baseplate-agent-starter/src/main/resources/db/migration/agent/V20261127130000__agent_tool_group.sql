-- 工具分组：控制台按分组管理、搜索与分页
ALTER TABLE agent_tool_definition ADD COLUMN IF NOT EXISTS group_name VARCHAR(64);
UPDATE agent_tool_definition SET group_name = '内置能力' WHERE builtin = TRUE AND group_name IS NULL;
CREATE INDEX IF NOT EXISTS idx_agent_tool_definition_group
    ON agent_tool_definition (tenant_id, group_name);
