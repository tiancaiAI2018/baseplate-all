-- 组件素材库内置样例：新租户打开「流程表单」时不至于一片空白，
-- 也顺带说明"片段可以存起来、在任意表单/页面复用"这个用法。
-- 只对 default / demo2 两个演示租户播种，真实租户不塞私货。
INSERT INTO wf_widget (tenant_id, code, name, category, rule_json, description)
VALUES
    ('default', 'widget_leave_basic', '请假基础信息', 'BASIC',
     $json$[
       {"type":"DatePicker","field":"leaveRange","title":"请假区间","props":{"type":"daterange","startPlaceholder":"开始","endPlaceholder":"结束","style":{"width":"100%"}}},
       {"type":"InputNumber","field":"days","title":"请假天数","props":{"min":0.5,"max":30,"step":0.5,"style":{"width":"180px"}},"validate":[{"required":true,"message":"请填请假天数"}]},
       {"type":"Input","field":"reason","title":"请假事由","props":{"type":"textarea","rows":3,"placeholder":"说明原因，主管审批会看这段"}}
     ]$json$::jsonb,
     '日期区间 + 天数 + 事由，三天以上会自动走会签'),

    ('default', 'widget_expense_table', '费用明细', 'BUSINESS',
     $json$[
       {"type":"Input","field":"project","title":"费用项目","props":{"placeholder":"如：差旅 / 招待"}},
       {"type":"InputNumber","field":"amount","title":"金额（元）","props":{"min":0,"precision":2,"style":{"width":"180px"}}},
       {"type":"Input","field":"invoiceNo","title":"发票号","props":{"placeholder":"没有发票可留空"}},
       {"type":"Upload","field":"attachments","title":"票据附件","props":{"action":"/api/files","limit":3,"listType":"text"}}
     ]$json$::jsonb,
     '一组费用字段，可直接插进报销类表单'),

    ('default', 'widget_approval_note', '审批意见', 'APPROVAL',
     $json$[
       {"type":"Radio","field":"decision","title":"审批结论","options":[{"label":"同意","value":"APPROVE"},{"label":"驳回","value":"REJECT"}],"validate":[{"required":true,"message":"请选择结论"}]},
       {"type":"Input","field":"opinion","title":"意见","props":{"type":"textarea","rows":2,"placeholder":"驳回时这里会做必填校验"}}
     ]$json$::jsonb,
     '结论 + 意见，配在会签/审批节点上很常见'),

    ('demo2', 'widget_leave_basic', '请假基础信息', 'BASIC',
     $json$[
       {"type":"DatePicker","field":"leaveRange","title":"请假区间","props":{"type":"daterange","style":{"width":"100%"}}},
       {"type":"InputNumber","field":"days","title":"请假天数","props":{"min":0.5,"max":30,"step":0.5}},
       {"type":"Input","field":"reason","title":"请假事由","props":{"type":"textarea","rows":3}}
     ]$json$::jsonb,
     'demo2 租户的独立副本，用来验证素材不跨租户')
ON CONFLICT (tenant_id, code, version) DO NOTHING;
