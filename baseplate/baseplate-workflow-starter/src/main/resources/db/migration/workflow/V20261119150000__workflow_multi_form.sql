-- 多表单改造：节点可绑定多个表单，字段权限改成「表单 → 字段 → 权限」二维结构；
-- 流程级基础表单（首个节点绑定、所有节点继承、不可删除）存在 wf_process_setting。
-- 开发期改造：存量数据可放弃，直接删列重建，不做兼容迁移。

ALTER TABLE wf_node_config DROP COLUMN IF EXISTS form_code;
ALTER TABLE wf_node_config ADD COLUMN IF NOT EXISTS form_codes JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN wf_node_config.form_codes IS '节点新增绑定的表单编码数组；实际表单 = 流程基础表单 ∪ 本数组';
COMMENT ON COLUMN wf_node_config.field_policy IS '二维：{formCode: {field: EDITABLE/READONLY/HIDDEN}}';

ALTER TABLE wf_process_setting DROP COLUMN IF EXISTS start_form_code;
ALTER TABLE wf_process_setting ADD COLUMN IF NOT EXISTS base_form_codes JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN wf_process_setting.base_form_codes IS '流程级基础表单编码数组，所有节点自动继承且不可删除';
