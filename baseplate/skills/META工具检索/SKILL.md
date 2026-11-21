---
name: META工具检索
description: META 模式总入口技能。任何业务操作前先加载本技能（skillId="META工具检索_baseplate"），学习如何用 tool_search / tool_describe / tool_call 发现并串起多个业务工具。
version: 1
modes: meta
---

# META 工具检索（元工具入口）

当前会话只提供 `tool_search` / `tool_describe` / `tool_call` 三个元工具。
业务能力都藏在它们后面，业务技能里提到的工具名都要通过 `tool_call` 执行。

## 三个元工具

| 目的 | 工具 | 参数 |
|------|------|------|
| 发现 | `tool_search` | `query`（可选）、`type`（internal/external/all）、`limit` |
| 确认 | `tool_describe` | `code` |
| 执行 | `tool_call` | `code`、`arguments`（JSON 对象） |

## 发现策略

- 不确定工具名时先 `tool_search`，用中文业务词或英文前缀：
  `{"query": "待办"}`、`{"query": "workflow"}`。
- 完全不确定就不传 `query`，列出全部，再 `tool_describe` 看参数。
- `type=external` 的工具由浏览器执行，`approval=ASK` 的工具需人工确认；
  两者都能用 `tool_call` 执行，入口会自动转给前端或弹出审批单。

## 串联多个工具

- 一次 `tool_call` 对应一个工具；需要按顺序执行时，连续调用多次。
- 需要把上一步结果填进下一步时，分两轮：第一轮拿到 id，第二轮把 id 写进
  `arguments`。例如先 `{"code": "workflow_list_my_started", "arguments": {...}}`
  拿到 `instanceId`，再 `{"code": "workflow_instance_progress", "arguments": {"instanceId": "..."}}`。
- 不要凭空构造 id、单号、表单编码；先查再填。

## 边界

- 只执行 `tool_search` 列出的工具，不猜 code，不把自然语言当 JSON 参数。
- 返回「缺少权限」就如实说明缺哪个权限点；返回「不能在当前页面使用」也照实说。
- 工具失败必须说明原因，不能声称成功。
- UI 卡片由前端渲染，正文里不要复述大段 JSON 或画 ASCII 图。
