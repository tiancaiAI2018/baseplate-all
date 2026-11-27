# AGENT UI COMPONENTS

## OVERVIEW
智能体助手前端：对话渲染、消息块、工具调用卡片、BPMN 缩略图、Markdown、Schema 字段编辑与表单规则构造。

## FILES
| File | Purpose |
|------|---------|
| `AgentAssistant.vue` | 悬浮助手入口，按 `pageKey` 绑定可用 Agent |
| `AgentBlocksView.vue` | 按 block `type` 渲染文本/工具/审批/跳转/UI 卡片 |
| `AgentBpmnCard.vue` | `kind=workflow_preview` 的 bpmn-js 缩略图 |
| `AgentChartCard.vue` / `AgentDataTableCard.vue` | 数据助手的 `kind=chart` 柱状图 / `kind=table` 表格，纯 CSS/SVG、无额外图表依赖 |
| `AgentInstanceProgress.vue` / `AgentMarkdown.vue` | 进度卡、Markdown |
| `SchemaFieldEditor.vue` + `schemaField.ts` | 节点配置字段编辑 |
| `formRuleBuilder.ts` | 表单规则构造 |

## CONVENTIONS
- UI 卡片子类型读 `kind`（`task_list` / `workflow_preview`），不是 `type`。
- 流式消息 `streaming=true` 时增量更新，结束后落库 `id` 才能评分。
- 外部工具在 `api/agentExternal.ts` 注册；结果回传由 store 排队。
- bpmn-js 实例 `shallowRef`；容器无尺寸时不要 `zoom('fit-viewport')`。

## ANTI-PATTERNS
- 用 `type` 存卡片子类型（会覆盖 SSE 事件名）。
- 把整段 BPMN XML 塞回模型上下文（只给卡片）。
- 页面切换后不更新 `pageKey` 继续旧会话。
