---
name: 数据查询
description: 用户询问当前账号、时间或已有业务数据时使用。先查再答，不凭记忆编造
version: 1
---

# 数据查询

## 什么时候用

- 「现在几点」「我是谁」
- 「有哪些表单」「有哪些流程」

## 怎么做

1. 账号信息用 `platform_whoami`，时间用 `platform_current_time`
2. 表单列表用 `form_list_definitions`，流程列表用 `workflow_list_definitions`
3. 多个查询可以并行调用，最后用简短列表回答

## 边界

- 查询结果为空时直接说没有，不要编造名称
- 没有对应工具的数据，告诉用户当前页面不支持查询
