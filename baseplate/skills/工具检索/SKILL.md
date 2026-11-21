---
name: 工具检索
description: 当前会话只提供 baseplate 命令行入口。任何业务查询或操作前先加载本技能，按 find / show / call 流程执行；不要臆造工具名。
version: 1
---

# 工具检索（baseplate 命令行）

## 何时使用

- 要在系统里查数据、看待办、找流程、看表单、查用户。
- 不确定有哪些工具，或不确定某个工具的参数。
- 其它技能正文里提到某个工具名（如 `workflow_list_my_tasks`）时，执行方式同样是 `call`，不要直接调用它。

## 怎么用

入口只有一个工具 `baseplate`，参数是脚本字符串 `script`，每行一条命令：

```
find <关键字> [--limit N] [--type internal|external]
show <toolCode>
call <toolCode> {json参数}
```

流程：

1. `find` 检索。用中文关键字或英文前缀，例如 `find 待办`、`find workflow`；不确定就不带关键字，列出全部可用工具。
2. `show` 确认参数。拿到 code 后查看参数 schema、权限点、审批级别。
3. `call` 执行。参数必须是严格 JSON 对象，没有参数写 `{}`。
4. 一次脚本可以连续多行，按顺序执行；需要串联时，把前一步结果自己填进下一步参数。

示例：

```
find 待办
show workflow_list_my_tasks
call workflow_list_my_tasks {"limit": 5}
```

## 边界

- 只执行 `find` 列出来的工具，不要猜 code，也不要把自然语言当参数。
- `type=external` 的工具由浏览器执行，`approval=ASK` 的工具需要用户确认。对它们的 `call` 会被拒绝并给出提示，按提示直接调用原工具，不要改写结果。
- 返回「缺少权限」时如实告诉用户缺哪个权限点，不要换工具重试。
- 返回「不能在当前页面使用」时说明该工具不属于当前页面。
- 工具失败必须如实说明原因，绝不能声称成功。

## 自检

- 每行开头只能是 `find`、`show` 或 `call`；不要写 shell 脚本、管道、重定向。
- `#` 开头的行会被忽略，可以用来分隔步骤。
