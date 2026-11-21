---
name: 工具元调用
description: 本会话只提供 tool_search / tool_describe / tool_call 三个元工具。开始任何业务操作前，用 load_skill_through_path(skillId="工具元调用_baseplate", path="SKILL.md") 加载本技能，按检索、确认、执行流程操作。
version: 2
---

# 工具元调用

## 何时使用

- 查数据、看待办、找流程、看表单、查用户等一切业务查询。
- 不确定有哪些工具，或不确定某个工具的参数。
- 其它技能正文里提到工具名时，也要用 `tool_call` 执行，不能直连工具名。

## 怎么用

| 目的 | 工具 | 参数 |
|------|------|------|
| 找工具 | `tool_search` | `query`（可选）、`type`（`internal`/`external`/`all`）、`limit` |
| 看参数 | `tool_describe` | `code` |
| 执行 | `tool_call` | `code`、`arguments`（JSON 对象） |

流程：

1. `tool_search` 检索。中文关键字或英文前缀都行；不确定就不传 `query`，列出全部。
2. `tool_describe` 确认参数 schema、权限点与审批级别。
3. `tool_call` 执行，`arguments` 必须是 JSON 对象，没有参数传 `{}`。
4. 一个 `tool_call` 对应一个工具；需要串联时把上一步结果自己填进下一步参数。
5. `type=external` 的工具用 `tool_call` 执行时会自动请求浏览器处理；`approval=ASK` 的工具会自动弹出审批单。等前端处理完成后再继续，不要谎报结果。

示例：

```
tool_search {"query": "待办"}
tool_describe {"code": "workflow_list_my_tasks"}
tool_call {"code": "workflow_list_my_tasks", "arguments": {"limit": 5}}
```

## 边界

- 只执行 `tool_search` 列出的工具，不猜 code。
- 当前会话只有三个元工具，不要尝试直接调用其它工具名。
- 返回「缺少权限」时如实告知缺哪个权限点，不要换工具重试。
- 返回「不能在当前页面使用」时说明该工具不属于当前页面。
- 工具失败必须如实说明原因，绝不能声称成功。
