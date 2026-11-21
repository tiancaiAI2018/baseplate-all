# BASEPLATE API (KERNEL CONTRACT)

## OVERVIEW
内核契约模块：端口、模型、注解、异常。被所有能力模块依赖，自身不依赖任何 Spring / MyBatis / Jackson / 业务模块。

## STRUCTURE
```
com.example.baseplate.api/
├── annotation/   # @RequiresPermission @OpenApi @AuditLog @RateLimit @Idempotent
│                 # @OneTimeLink @SignatureRequired
├── exception/    # BaseplateException AuthenticationException ForbiddenException RateLimitedException
├── model/        # CurrentUser TenantId ModuleDescriptor PermissionDef MenuDef PageQuery/PageResult
│                 # Decision RuleResult Workflow*/Process*/Task* 等 record
└── port/out/     # AuthenticationPort CurrentUserProvider DataScopeProvider ModuleRegistry
                  # ProcessEnginePort RuleEvaluator UserDirectory CacheProvider KeyValueStore
                  # IdempotencyStore NonceStore OneTimeTokenStore RateLimiter Clock IdGenerator
                  # DomainEventPublisher
```

## CONVENTIONS
- 只放契约：接口、record、枚举、注解、异常。零实现、零框架依赖。
- 数据载体一律 `record`，不手写 getter/setter。
- 端口命名 `*Port` 或能力名（`CacheProvider`），方法参数显式带 `tenantId`。
- 注解语义不得在模块内解释；由各 starter 的切面/过滤器实现。
- 新增端口需在 `baseplate-contract` 的 TCK 或 ArchUnit 测试中守住边界。

## ANTI-PATTERNS
- 引入 Spring / MyBatis / Jackson / slf4j。
- 依赖任何 `baseplate-*-starter` 或业务包（`PomBoundaryTest` 会失败）。
- 放业务逻辑、SQL、HTTP DTO 转换。
- `@OpenApi` 被标到 `/openapi/**` 之外的处理器（启动期硬失败）。
- 删除已声明的权限点（只能停用）。

## TESTS
`baseplate/baseplate-contract/src/test/java/.../arch/PomBoundaryTest.java` 与 `ReverseCompileProbeTest.java` 守依赖方向；改动本模块后运行 `mvn -pl baseplate-contract -am test`。
