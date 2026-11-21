# BASEPLATE ADAPTERS

## OVERVIEW
端口适配器实现层：Redis（生产）、内存（无 Redis 降级）、MyBatis-Plus 持久化，以及自动装配。

## STRUCTURE
```
com.example.baseplate.adapter/
├── out/redis/        # Redis{KeyValue,Cache,Idempotency,Nonce,OneTimeToken,RateLimiter}
├── out/memory/       # InMemory* 同端口降级实现
├── out/persistence/mybatis/  # ModuleRegistryRepository + DO/Mapper
├── config/           # MybatisPlusConfig
└── autoconfigure/    # RedisPorts / MemoryPorts / Persistence 条件装配
resources/db/migration/baseplate/  # bp_module_registry + core_tables
```

## CONVENTIONS
- 每个内存实现必须通过 `baseplate-contract` 的 TCK 才能算可用。
- Redis 版与内存版语义必须一致：TTL、幂等键、nonce 一次性、限流窗口。
- 装配用 `@ConditionalOnClass` 优先；`baseplate.redis.fallback-to-memory` 控制降级。
- 新增端口先加 `baseplate-api` 接口 + TCK，再实现两个适配器。

## ANTI-PATTERNS
- 把内存限流/幂等用于多实例生产（只在单实例准确）。
- 在适配器里写业务规则。
- 直接改已执行的 Flyway 脚本。
- 绕过 `PortRegistry` 手工 new 实现。

## TESTS
`src/test/.../InMemory*TckTest.java` × 6；`RealPostgresMigrationTest` 需要真实 PG。
