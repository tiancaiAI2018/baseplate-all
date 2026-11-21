# COMPOSABLES

## OVERVIEW
可复用组合逻辑。目前 `useUserDirectory.ts`：模块级缓存的人员名录，供办理抽屉、进度轨迹、发起配置共用。

## CONVENTIONS
- 模块级 `ref` + 单次 `loading` Promise 去重，避免每个组件各拉一次。
- 请求走 `api/client`。
- 失败降级为空数组，不抛到界面。
- 新增 composable 保持「无 UI、可复用、可单测」。

## ANTI-PATTERNS
- 在 composable 里操作 DOM 或路由。
- 每次调用都重新请求同一份目录数据。
