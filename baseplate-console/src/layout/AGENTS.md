# CONSOLE LAYOUT

## OVERVIEW
主壳 `ConsoleLayout.vue`：侧边栏（品牌、菜单分组、待办角标）、折叠、顶部用户区。

## CONVENTIONS
- 菜单项 `index` 必须与路由路径一致。
- 分组/菜单按权限显示：`auth.has('wf:task')` 等，不要只靠后端 403。
- 待办角标来自 `workflow.todoCount`（>99 显示 `99+`）。
- 折叠状态只影响展示，不影响路由。

## ANTI-PATTERNS
- 在 layout 里写业务请求（角标刷新放 store）。
- 菜单路径与 `router/index.ts` 不一致。
