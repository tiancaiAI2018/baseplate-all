# PINIA STORES

## OVERVIEW
三个 store：`auth`（登录态/权限）、`workflow`（待办角标）、`agent`（对话、SSE 流、外部工具排队、HITL）。

## CONVENTIONS
- `auth`：令牌与用户信息；`has(code)` 判断权限。
- `workflow`：只维护 `todoCount`，按权限懒刷新，失败不影响页面。
- `agent`：消息块模型、流式更新、`pendingExternal` 排队；SSE 未结束时外部结果先入队，结束再 flush。
- 评分依赖后端回传的消息 `id`；历史回放来自列表接口。
- 外部工具 handler 从 `api/agentExternal.ts` 按工具 code 取。

## ANTI-PATTERNS
- 在组件里复制一份对话状态。
- 流进行中直接回传外部工具结果。
- 把 `type` 当卡片子类型（用 `kind`）。
- store 里做路由跳转（返回结果给页面处理）。
