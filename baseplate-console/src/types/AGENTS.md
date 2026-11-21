# FRONTEND TYPES

## OVERVIEW
前端领域类型。目前 `cend.ts`：C 端页面 DSL（`CendBlock` / `CendTheme` / `CendPageDsl` / `CendPageView`）。

## CONVENTIONS
- 块 `type` 联合类型必须与 `components/cend/cendDsl.ts` 的 `BLOCK_TYPES` 一致。
- 类型只描述传输结构，不放运行时校验；校验在白名单（`cendDsl.ts`）。
- 新增后端 DTO 时同步类型；后端用包装类型，前端可空字段用 `?`。

## ANTI-PATTERNS
- 用 `any` 绕过结构定义。
- 类型与后端字段命名不一致（后端 JSON 通常 camelCase）。
