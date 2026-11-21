# CONSOLE API LAYER

## OVERVIEW
全部后端调用的唯一出口。`client.ts` 创建 axios 实例并处理令牌与错误跳转；其余文件按领域封装。

## STRUCTURE
| File | Purpose |
|------|---------|
| `client.ts` | 唯一 axios 实例、`TOKEN_KEY`、401/改密跳转、`errorText()` |
| `agent.ts` | Agent 定义/会话/观测/技能等 REST |
| `agentStream.ts` | 对话 SSE 流（chat / external result / HITL decision） |
| `agentExternal.ts` | 外部工具处理器注册表（按工具 code 取 handler） |
| `agentCanvas.ts` | 外部工具读画布 |
| `cendDesigner.ts` / `cendActions.ts` | C 端页面设计/发布/提交 |
| 其他领域 api | 按页面需要新增 |

## CONVENTIONS
- 所有请求 `import http from './client'`，禁止 `import axios`。
- 错误提示统一 `errorText(error)`，不要自拼。
- 令牌默认注入；调用方显式设置 `Authorization` 时不得覆盖（演练页跨租户需要）。
- SSE 用 `fetch` 流式读取（axios 不适合），事件 `type` 为事件名。

## ANTI-PATTERNS
- 页面直接 `axios.get(...)`。
- 在 api 层做 UI 跳转（除 `client.ts` 的 401/403 约定）。
- 把外部工具结果在流未结束时回传。
