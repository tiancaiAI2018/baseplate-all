---
name: 工具元调用
description: 当前会话通过 tool_search / tool_describe / tool_call 三个元工具访问业务工具。任何业务查询或操作前先加载本技能。
version: 1
---

# 工具元调用

## 何时使用

- 要在系统里查数据、看待办、找流程、看表单、查用户。
- 不确定有哪些工具，或不确定某个工具的参数。
- 其它技能正文里提到某个工具名（如 `workflow_list_my_tasks`）时，用 `tool_call` 执行，不要直接调用它。

## 怎么用

| 目的 | 工具 | 参数 |
|------|------|------|
| 找工具 | `tool_search` | `query`（可选）、`type`（`internal` / `external` / `all`）、`limit` |
| 看参数 | `tool_describe` | `code` |
| 执行 | `tool_call` | `code`、`arguments`（JSON 对象） |

流程：

1. `tool_search` 检索。用中文关键字或英文前缀；不确定就不传 `query`，列出全部。
2. `tool_describe` 确认参数 schema、权限点、审批级别。
3. `tool_call` 执行，`arguments` 必须是 JSON 对象，没有参数传 `{}`。
4. 需要串联时，把上一步结果自己填进下一步 `arguments`。

示例：

```
tool_search {"query": "待办"}
tool_describe {"code": "workflow_list_my_tasks"}
tool_call {"code": "workflow_list_my_tasks", "arguments": {"limit": 5}}
```

## 边界

- 只执行 `tool_search` 列出来的工具，不要猜 code。
- 当前会话只有三个元工具，不能直连其它工具名。`type=external` 的工具用 `tool_call` 执行时会自动请求浏览器处理，`approval=ASK` 的工具会自动弹出审批单；等前端处理完成再继续，不要改写结果或声称已执行。
- 返回「缺少权限」时如实告诉用户缺哪个权限点，不要换工具重试。
- 返回「不能在当前页面使用」时说明该工具不属于当前页面。
- 工具失败必须如实说明原因，绝不能声称成功。
