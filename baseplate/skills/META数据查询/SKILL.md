---
name: META数据查询
description: META 模式下的账号、时间、表单与流程目录查询。先加载 META工具检索 学会元工具语法，再用本技能组织查询与回答。
version: 1
modes: meta
---

# META 数据查询

## 什么时候用

- 「现在几点」「我是谁」→ `platform_current_time`、`platform_whoami`
- 「有哪些表单」「有哪些流程」→ `form_list_definitions`、`workflow_list_definitions`

## 怎么用（元工具形式）

```
tool_call {"code": "platform_whoami", "arguments": {}}
tool_call {"code": "platform_current_time", "arguments": {}}
tool_call {"code": "form_list_definitions", "arguments": {}}
tool_call {"code": "workflow_list_definitions", "arguments": {}}
```

多个独立查询可以连续调用，最后用简短列表回答。
不确定工具名或参数时，先用 META工具检索 技能里的 `tool_search` / `tool_describe` 确认。

## 边界

- 查询结果为空就直说没有，不要编造名称或编码。
- 当前页面没有对应工具时，说明该页面不支持这类查询。
- 时间用工具返回的 ISO 值换算，不凭记忆回答。
