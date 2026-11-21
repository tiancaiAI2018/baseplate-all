# ROUTER

## OVERVIEW
`createWebHashHistory` 哈希路由；登录守卫、首登改密守卫。

## ROUTES
- 公开：`/login`、`/c/:code`（C 端页面）、`/oauth/authorize`（需登录）。
- 主壳 `ConsoleLayout`：dashboard、identity/*、platform/*、audit/logs、rule/config、workflow/*、cend/*、agent/*。
- 兜底 `/:pathMatch(.*)*` → `/dashboard`。

## CONVENTIONS
- 新增页面：在 `views/` 建组件 → 在 `index.ts` 加路由（懒加载）→ 模块清单加菜单/权限。
- 未登录跳登录并带 `redirect`（授权页登录后要能回跳）。
- `auth.user.mustChangePassword` 时强制去 `/change-password`。
- 菜单可见性在 `ConsoleLayout` 用 `auth.has(...)` 控制。

## ANTI-PATTERNS
- 用 history 模式（静态资源部署会 404）。
- 把权限判断只写在前端。
- 路由 meta 与模块清单菜单路径不一致。
