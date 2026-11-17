# 01 · 架构与分层

## 本文档解决什么

读完你能回答：代码该放哪个模块、能依赖谁、新能力通过什么扩展、模块清单里每个字段是什么意思。

---

## 一、设计立场

三条，决定了后面所有规则：

1. **六边形 / 端口-适配器**。内核只认接口，基础设施（Redis、Flowable、MyBatis）都在外面。
   端口按**目的**定义而不是按技术定义——是 `RateLimiter` 而不是 `RedisTemplate`，是 `ProcessEnginePort` 而不是 `RuntimeService`。
2. **DDD 分层但不过度教条**。只有能力模块内部讲究 `domain / application / adapter`，
   共享的 `AggregateRoot` 只有一个类，不强行给每个业务造聚合根。
3. **模块化单体**。一个进程、一个数据库、一次部署；模块边界靠 Maven 依赖方向 + 清单声明守住，
   将来要拆服务时按模块边界切，不用重写。

---

## 二、分层与依赖规则

```
                        ┌──────────────────────────┐
                        │   baseplate-bootstrap    │  唯一可执行制品
                        └────────────┬─────────────┘
                                     │ 依赖全部 starter
        ┌──────────────┬─────────────┼──────────────┬──────────────┐
        │              │             │              │              │
   security-      identity-      audit-        platform-      workflow- / rule- / metadata-
   starter        starter        starter       starter        starter
        │              │             │              │              │
        └──────────────┴──────┬──────┴──────────────┴──────────────┘
                              │
                    ┌─────────┴─────────┐
                    │  runtime/registry │  端口注册、清单解析
                    └─────────┬─────────┘
                    ┌─────────┴─────────┐
                    │    adapters       │  MyBatis / Flyway / Redis / 内存降级
                    └─────────┬─────────┘
                    ┌─────────┴─────────┐
                    │      api          │  端口 + 模型 + 注解 + 异常（零框架依赖）
                    └───────────────────┘
```

### 硬规则

| 规则 | 由谁保证 |
|---|---|
| `baseplate-api` 不得依赖 spring / mybatis / redis / flowable / jackson / junit | `baseplate-contract` 的 `PomBoundaryTest.apiHasNoThirdPartyDependency` |
| `baseplate-domain` 不得依赖任何框架（含 jpa/hibernate/jdbc） | `PomBoundaryTest.domainHasNoFrameworkDependency` |
| 能力 starter 之间不得互相依赖实现类，只能依赖 `api` 里的端口 | 代码评审 + 端口设计（见下） |
| 业务模块只能依赖 `baseplate-api`（编译期）和 `baseplate-starter`（运行期装配） | 同上 |

### 能力之间怎么协作

不靠互相 import，靠**端口 + `ObjectProvider` 可选注入**。例子：开放平台要校验「被代理用户的权限」，
但它不能依赖 identity 模块：

```java
// baseplate-api 定义
public interface UserDirectory { Optional<Snapshot> load(String userId); }

// identity 提供实现（@ConditionalOnMissingBean）
// platform 可选注入，拿不到就明确拒绝，而不是放行
public OpenApiAuthFilter(..., UserDirectory userDirectory) { ... }
```

所以「只装 platform 不装 identity」是合法组合：USER 类型接口会返回 `503 USER_DIRECTORY_UNAVAILABLE`。

---

## 三、16 个模块的职责

| 模块 | 职责 | 有代码? |
|---|---|---|
| `baseplate-api` | 端口、模型、注解、异常。**内核契约** | 47 个类 |
| `baseplate-domain` | 共享领域原语 | 1 个（`AggregateRoot`） |
| `baseplate-application` | 预留的跨模块应用服务位 | 空（只有 `package-info`） |
| `baseplate-runtime` | 端口注册表、配置解密、租户上下文、`baseplate.*` 配置 | 9 |
| `baseplate-registry` | 模块清单加载/校验、迁移计划生成 | 8 |
| `baseplate-starter` | 聚合 starter，业务方引它 | 0（只有配置模板） |
| `baseplate-contract` | 6 个端口的 TCK + 架构边界测试 | 7 + 测试 |
| `baseplate-adapters` | Redis 六件套、内存降级、MyBatis-Plus、Flyway | 19 |
| `baseplate-security-starter` | JWT 鉴权链、限流/幂等/一次性链接拦截器、权限切面 | 18 |
| `baseplate-identity-starter` | 用户/角色/组织/权限目录、登录、密码策略 | 23 |
| `baseplate-audit-starter` | 访问/业务/登录三类审计 + 查询接口 | 5 |
| `baseplate-platform-starter` | 开放平台：双凭证、注解暴露、主体区分、授权码 | 21 |
| `baseplate-workflow-starter` | Flowable 8 封装（BPMN 校验、部署、发起、办理） | 5 |
| `baseplate-rule-starter` | 结构化规则引擎（字段+运算符+值） | 10 |
| `baseplate-metadata-starter` | 低代码元数据（FORM/LIST/FLOW 三类 Schema） | 3 |
| `baseplate-bootstrap` | 唯一可执行制品，内嵌前端静态资源 | 3 |

> `baseplate-application` 与 `baseplate-starter` 目前**没有实际逻辑**。前者是留给将来跨模块编排的位置，
> 后者只是把依赖打包给业务方。不要往这两个地方塞业务代码。

---

## 四、模块清单机制

每个能力 starter 的 `src/main/resources/META-INF/baseplate-module.yml` 声明自己的一切。
启动时 `ModuleDescriptorLoader` 用 `ClassLoader.getResources` **跨 jar** 扫出来，合并成一份注册表。

### 完整字段表

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 模块唯一标识，与包名对应，如 `identity` |
| `display-name` | ✅ | 中文名，控制台分组标题用它 |
| `version` | ✅ | 模块版本 |
| `requires` | | 兼容的 api 版本范围，如 `"baseplate-api >=1.0.0,<2.0.0"` |
| `depends-on` | | 强依赖的其他模块 id 列表；缺失则启动失败 |
| `root-package` | | 模块根包 |
| `enabled-by-default` | | 默认是否启用 |
| `permissions` | | 权限点列表：`{ code, label, type }`，`type` 取 `MENU` / `BUTTON` |
| `flyway-locations` | | 该模块的迁移脚本位置，如 `classpath:db/migration/identity` |
| `openapi-group` | | 该模块开放接口的默认分组名 |
| `menus` | | 菜单：`{ path, label, permission, icon }` |
| `rule-fields` | | 规则引擎可用的字段：`{ name, label, type, options? }` |
| `permission-defs` | | 与 `permissions` 同义的结构化版本（带中文标签），新代码用这个 |

最小示例：

```yaml
id: crm
display-name: 客户管理
version: 1.0.0
root-package: com.example.baseplate.crm
enabled-by-default: true

permissions:
  - { code: "customer:view", label: 查看客户, type: MENU }
  - { code: "customer:create", label: 新建客户, type: BUTTON }

menus:
  - { path: /crm/customers, label: 客户列表, permission: "customer:view", icon: User }

flyway-locations: classpath:db/migration/crm

rule-fields:
  - { name: customerLevel, label: 客户等级, type: STRING, options: [NORMAL, VIP] }
```

### 启动流程

```
1. ModuleDescriptorLoader    扫所有 jar 的 META-INF/baseplate-module.yml
2. ModuleDescriptorValidator 校验：id 唯一、depends-on 存在、权限编码格式、flyway 路径存在
                              └─ baseplate.registry-fail-fast=true 时任何一个不合规 → 启动失败
3. MigrationPlanWriter       汇总所有 flyway-locations → build/migration-plan.json（不执行）
4. Flyway                    spring.flyway.enabled=true 时按 locations 执行（生产由流水线跑）
5. ModuleRegistryPersistenceRunner  把模块状态写进 bp_module_registry 表
6. 各能力 starter 的 ApplicationRunner/SmartInitializingSingleton 再做自己的初始化
   ├─ PermissionCatalogRepository.sync   权限点 → sys_permission（人工改过的不覆盖）
   ├─ OpenApiCatalogRepository.ensureCatalog  @OpenApi 扫描结果 → open_api（人工改过的不覆盖）
   └─ AdminSeeder                        首次启动建 admin 与 SUPER_ADMIN 角色
```

**顺序很重要**：清单扫描发生在 `ApplicationRunner` 之前，所以权限目录同步一定拿得到完整模块列表。

---

## 五、16 个端口

| 端口 | 目的 | Redis 实现 | 内存降级 |
|---|---|---|---|
| `AuthenticationPort` | JWT 签发 + 注销黑名单 | ✅ | ✅ |
| `CacheProvider` | 类型化缓存，key 必须含 tenant | ✅ | ✅ |
| `Clock` | 可注入时钟，测试用固定时钟 | — | — |
| `CurrentUserProvider` | 当前用户上下文，`withUser` 显式传递语义 | — | — |
| `DataScopeProvider` | 行级数据权限，显式下传 | — | — |
| `DomainEventPublisher` | 领域事件，至少一次投递 | — | — |
| `IdGenerator` | ID 生成（生产 UUIDv7） | — | — |
| `IdempotencyStore` | 写接口幂等 | ✅ | ✅ |
| `KeyValueStore` | 单键 KV + TTL | ✅ | ✅ |
| `ModuleRegistry` | 只读查询模块清单 | — | — |
| `NonceStore` | 签名 nonce 去重（原子 `SET NX EX`） | ✅ | ✅ |
| `OneTimeTokenStore` | 一次性链接，`purpose` 参与比较防跨用途重放 | ✅ | ✅ |
| `ProcessEnginePort` | 流程引擎，业务只认这个接口 | Flowable | 占位实现（调用即报错） |
| `RateLimiter` | 限流；多实例必须 Redis + Lua | ✅ | ✅（仅单实例有效） |
| `RuleEvaluator` | 规则求值，Dry-run 与正式共用实现 | — | — |
| `UserDirectory` | 按用户 ID 取身份快照（开放平台代用户调用） | — | identity 提供 |

**一条强约束**：每个端口在容器里**只能有一个** Bean，由 `PortRegistry` 校验，重复直接启动失败。
想换实现就替换 Bean，不要在业务代码里写 `if (redisAvailable)`。

---

## 六、扩展点

### 加一个端口

1. `baseplate-api/.../port/out/XxxPort.java` 定义接口，方法参数只允许 api 里的模型与 JDK 类型。
2. `baseplate-adapters/.../redis/` 与 `.../memory/` 各给一个实现（能降级的端口必须两套）。
3. 在对应 `AutoConfiguration` 里 `@Bean @ConditionalOnMissingBean`，并用 `@ConditionalOnProperty` 切换。
4. `baseplate-contract/.../tck/` 补一个 TCK，两套实现都跑同一套用例。

**不要**在端口里出现 `JdbcTemplate`、`RedisTemplate`、`RuntimeService` 这类技术类型。

### 加一个能力 starter

1. 建模块，`pom.xml` 只依赖 `baseplate-api`（需要 Spring 时依赖 `spring-context`，不要依赖其他 starter）。
2. 写 `META-INF/baseplate-module.yml`。
3. 写 `XxxAutoConfiguration`，在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 里注册。
4. 条件装配：`@ConditionalOnBean(DataSource.class)` + `@ConditionalOnClass(JdbcTemplate.class)`，
   **不要用 `@ConditionalOnBean(JdbcTemplate.class)`**——它比 JdbcTemplate 自动配置更早求值，永远不成立（踩过）。
5. 迁移脚本放 `src/main/resources/db/migration/<模块>/`。
6. 在 `baseplate-bootstrap/pom.xml` 加依赖 + `application.yml` 的 `spring.flyway.locations` 加一项。

### 加一个业务模块（不改基盘）

见 [05-business-module.md](05-business-module.md)，那是给业务方看的主路径。

---

## 七、反模式

| 写法 | 为什么不行 |
|---|---|
| 业务代码里 `new RedisTemplate(...)` | 内核就被绑死在 Redis 上，降级失效 |
| `baseplate-api` 里 import `org.springframework.*` | `PomBoundaryTest` 会失败，且拉崩内核可移植性 |
| 能力 starter A 直接 import 能力 starter B 的 `Service` | 拆服务时拆不动；用端口 + `ObjectProvider` |
| 在 Controller 里写业务规则 | 规则要能被规则引擎和开放接口复用，放 Service |
| 用 MyBatis-Plus 的 `DataPermissionInterceptor` 做数据权限 | 隐式织入，出问题极难排查；本项目显式 `DataScope` 下传 |
| 给每个业务实体都造 `AggregateRoot` 子类 | 只有真正有不变式边界的才需要，其余用普通 record/PO |
| 在 `baseplate-application` 塞业务逻辑 | 那是预留位，不是业务层 |
