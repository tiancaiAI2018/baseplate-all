# SECURITY STARTER

## OVERVIEW
安全链路：JWT 校验、登录态主体、权限切面、统一异常映射、限流/幂等/一次性链接拦截器、首登改密、TraceId。

## STRUCTURE
```
com.example.baseplate.security/
├── security/SecurityConfig.java、PermissionAspect.java
├── jwt/JwtVerifier、JwtClaims、TokenBlacklist
├── web/JwtAuthenticationFilter、CurrentUserProviderImpl、MustChangePasswordFilter、
│       TraceIdFilter、CachedResponseFilter、WebMvcConfig、
│       RateLimitInterceptor、IdempotencyInterceptor、OneTimeLinkInterceptor、
│       ApiExceptionHandler、RateLimitSubject
└── config/SecurityProperties.java
```

## CONVENTIONS
- 过滤器顺序：TraceId → 访问日志 → JWT → 首登改密 → MVC 拦截器（限流/幂等/一次性链接）。
- `JwtAuthenticationFilter` 必须 `shouldNotFilter` 跳过 `/openapi/`，否则第三方令牌被 401 短路。
- 权限用 `@RequiresPermission("模块:动作")`，权限点必须有模块清单声明，否则永远 403。
- 异常统一在 `ApiExceptionHandler`：400 / 401 / 403 / 409 / 429；不要 `new RuntimeException`。
- `TokenBlacklist` 的用户级吊销用严格大于 `revokeEpochSecond > token.iat`；登录成功清除标记。
- 幂等拦截器只注册一次（自动配置或组件扫描二选一）。

## ANTI-PATTERNS
- 关闭开放令牌与 JWT 密钥差异自检。
- Controller 里手写权限判断。
- 在限流/幂等里用非业务 key。
- 重复注册拦截器导致同一请求消费两次 nonce。
