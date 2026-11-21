# AUDIT STARTER

## OVERVIEW
三类审计：访问审计（全部请求）、业务审计（`@AuditLog`）、登录审计。审计写库是旁路，失败只告警，不能影响响应。

## STRUCTURE
```
com.example.baseplate.audit/
├── adapter/in/web/AccessLogFilter.java   # 访问审计 + 追踪
├── adapter/in/web/AuditQueryController.java
├── application/AuditAspect.java          # 织入 @AuditLog（加在 Service）
├── application/AuditWriter.java          # 异步落库
├── autoconfigure/AuditAutoConfiguration.java
resources/db/migration/audit/             # 索引 / detail 列
resources/META-INF/baseplate-module.yml   # audit:view / 菜单 / 保留天数
```

## CONVENTIONS
- `@AuditLog(action="模块:动作", resource="表名")` 只加在 Service 方法。
- 参数脱敏（密码、令牌）由 `AuditAspect` 统一处理，Controller 不重复做。
- 查询 `jsonb` 列必须 `detail::text`，否则前端拿到 `{"type":"jsonb","value":...}`。
- 开放平台请求没有 `tenant_id`：`OpenApiAuthFilter` 写 request 属性（`openapi.appKey/appName/appTenant/actUser`），`AccessLogFilter` 据此落库，否则按租户过滤会整行消失。

## ANTI-PATTERNS
- 在业务代码里再记一条请求日志（与 `AccessLogFilter` 重复）。
- 审计失败抛异常打断主流程。
- 日志里带密码/令牌/完整手机号。
