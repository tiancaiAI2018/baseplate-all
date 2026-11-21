---
name: CLI工具检索
description: CLI 模式总入口技能。任何业务操作前先加载本技能（skillId="CLI工具检索_baseplate"），学习如何用 baseplate 的 find/show/call 发现并串起多个业务工具。
version: 1
modes: cli
---

# CLI 工具检索（业务入口）

当前会话只提供 `baseplate` 一个业务工具。所有业务能力都藏在它的 `script` 里，
靠 `find` 发现、`show` 确认、`call` 执行。业务技能里提到的工具名，都要翻译成 CLI 命令。

## 三个子命令

```
find <关键字> [--limit N] [--type internal|external]   发现工具
show <toolCode>                                        查看参数、权限、审批级别
call <toolCode> {json参数}                             执行工具
```

## 发现策略

- 不确定工具名时先 `find`，关键字用中文业务词或英文前缀都行：
  `find 待办`、`find 表单`、`find 流程`、`find canvas`。
- 完全不确定就 `find`（不带关键字）列出全部，再 `show` 看参数。
- `type=external` 的工具由浏览器执行，`approval=ASK` 的工具要人工确认；
  两者都能用 `call` 执行，入口会自动转给前端或弹出审批单。

## 串联多个工具

- 一个 `script` 可以写多行按顺序执行，避免反复往返模型：
  ```
  find 待办
  show workflow_list_my_tasks
  call workflow_list_my_tasks {"limit": 10}
  ```
- 需要把上一步结果填进下一步时，分两轮调用：第一轮 `call` 拿到 id，
  第二轮把 id 写进下一个 `call` 的参数。例如先拿 `instanceId`，再查进度。
- 不要凭空构造 id、单号、表单编码；先查再填。

## 边界

- 只执行 `find` 列出的工具，不猜 code，不把自然语言当 JSON 参数。
- 返回「缺少权限」就如实说明缺哪个权限点；返回「不能在当前页面使用」也照实说。
- 工具失败必须说明原因，不能声称成功。
- UI 卡片（任务列表、进度图、表单画布）由前端渲染，正文里不要复述大段 JSON 或画 ASCII 图。
