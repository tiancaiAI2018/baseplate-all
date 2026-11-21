---
name: CLI数据查询
description: CLI 模式下的账号、时间、表单与流程目录查询。先加载 CLI工具检索 技能学会 call 语法，再用本技能组织查询与回答。
version: 1
modes: cli
---

# CLI 数据查询

## 什么时候用

- 「现在几点」「我是谁」→ `platform_current_time`、`platform_whoami`
- 「有哪些表单」「有哪些流程」→ `form_list_definitions`、`workflow_list_definitions`

## 怎么用（CLI 形式）

所有调用都写进 `baseplate` 的 `script`：

```
call platform_whoami {}
call platform_current_time {}
call form_list_definitions {}
call workflow_list_definitions {}
```

多个独立查询可以放在同一个脚本里一次执行，最后用简短列表回答：

```
call platform_whoami {}
call form_list_definitions {}
```

不确定工具名或参数时，先用 CLI工具检索 技能里的 `find` / `show` 确认。

## 边界

- 查询结果为空就直说没有，不要编造名称或编码。
- 当前页面没有对应工具时，说明该页面不支持这类查询。
- 时间用工具返回的 ISO 值换算，不凭记忆回答。
