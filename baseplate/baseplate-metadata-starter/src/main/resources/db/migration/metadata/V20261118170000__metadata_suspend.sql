-- 元数据作废标记：与工作流定义一致，作废只挡新引用，
-- 已经被流程节点或页面绑定的表单仍能按原版本渲染。
ALTER TABLE meta_schema ADD COLUMN IF NOT EXISTS suspended BOOLEAN NOT NULL DEFAULT FALSE;
