# BASEPLATE CONTRACT

## OVERVIEW
端口契约验证：TCK（技术兼容性套件）+ ArchUnit/POM 边界测试。保证所有适配器行为一致、依赖方向不被破坏。

## STRUCTURE
```
com.example.baseplate.contract/
├── fixture/MutableClock.java
├── tck/{CacheProvider,IdempotencyStore,KeyValueStore,NonceStore,
│        OneTimeTokenStore,RateLimiter}Tck.java
└── (test) arch/PomBoundaryTest.java、arch/ReverseCompileProbeTest.java
```

## CONVENTIONS
- 新增 `baseplate-api` 端口时，先写 TCK，再让 Redis 与内存实现共用同一套断言。
- 边界测试用 ArchUnit 表达「api 不依赖业务模块 / 不引入框架」。
- 测试放这里，不在各 starter 里重复写契约。

## ANTI-PATTERNS
- 只给一个适配器写测试、另一个靠「看起来一样」。
- 放宽 ArchUnit 规则来让编译通过。
- 在 TCK 里依赖 Spring 容器。
