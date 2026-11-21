---
name: 工具检索
description: 本会话只提供 baseplate 命令行入口。开始任何业务操作前，用 load_skill_through_path(skillId="工具检索_baseplate", path="SKILL.md") 加载本技能，按 find / show / call 流程执行。
version: 2
---

# 工具检索（baseplate 命令行）

## 何时使用

- 查数据、看待办、找流程、看表单、查用户等一切业务查询。
- 不确定有哪些工具，或不确定某个工具的参数。
- 其它技能正文里提到工具名时，也要用 `call` 执行，不能直连工具名。

## 怎么用

唯一入口是 `baseplate`，参数 `script` 是脚本，每行一条命令：

```
find <关键字> [--limit N] [--type internal|external]
show <toolCode>
call <toolCode> {json参数}
```

流程：

1. `find` 检索。中文关键字或英文前缀都行，例如 `find 待办`、`find workflow`；完全不确定就不带关键字，列出全部。
2. `show` 看参数、权限点、审批级别，再决定要不要执行。
3. `call` 执行。参数必须是严格 JSON 对象，没有参数写 `{}`。
4. 一个脚本可以写多行按顺序执行；需要串联时把前一步结果自己填进下一步参数。
5. `type=external` 的工具用 `call` 执行时，会自动请求浏览器处理；`approval=ASK` 的工具会自动弹出审批单。等前端处理完成后再继续，不要谎报结果。

示例：

```
find 待办
show workflow_list_my_tasks
call workflow_list_my_tasks {"limit": 5}
```

## 边界

- 只执行 `find` 列出的工具，不猜 code，不把自然语言当参数。
- 当前会话只有 `baseplate` 一个业务工具，不要尝试直接调用其它工具名。
- 返回「缺少权限」时如实告知缺哪个权限点，不要换工具重试。
- 返回「不能在当前页面使用」时说明该工具不属于当前页面。
- 工具失败必须如实说明原因，绝不能声称成功。

## 自检

- 每行开头只能是 `find`、`show`、`call`；不要写 shell 脚本、管道、重定向。
- `#` 开头的行会被忽略，可用来分隔步骤。
