# BASEPLATE BACKEND

## OVERVIEW
Spring Boot 4.1.2 / Java 21 模块化单体。19 个 Maven 模块（10 个能力 starter），唯一可执行制品 `baseplate-bootstrap`；能力模块通过 `META-INF/baseplate-module.yml` 声明权限、菜单、Flyway 目录。注意：README「现状」里的 18 已过期。

## MODULE MAP
| Module | Responsibility |
|--------|----------------|
| `baseplate-api` | 内核契约：端口、模型、注解、异常。零 Spring/MyBatis/Jackson |
| `baseplate-domain` | 共享领域原语（`AggregateRoot`） |
| `baseplate-application` | 预留扩展位（仅 package-info） |
| `baseplate-runtime` | 端口注册表、配置解密、租户上下文 |
| `baseplate-registry` | 模块清单解析/校验、迁移计划 |
| `baseplate-starter` | 聚合 starter（业务方入口） |
| `baseplate-contract` | 端口 TCK + ArchUnit 边界测试 |
| `baseplate-adapters` | MyBatis-Plus / Flyway / Redis / 内存降级 |
| `baseplate-security-starter` | 鉴权链、JWT、限流、幂等、一次性链接 |
| `baseplate-identity-starter` | 用户/角色/组织/权限目录 |
| `baseplate-audit-starter` | 访问/业务/登录三类审计 |
| `baseplate-platform-starter` | 开放平台（双凭证、注解暴露、授权码） |
| `baseplate-workflow-starter` | Flowable 8 封装、流程草稿、节点配置 |
| `baseplate-rule-starter` | 结构化规则引擎 |
| `baseplate-metadata-starter` | 低代码元数据 |
| `baseplate-agent-starter` | 智能体：定义/会话/工具/技能/HITL/SSE |
| `baseplate-agent-workflow-starter` | workflow/form 工具声明 |
| `baseplate-cend-starter` | C 端页面公开接口与智能体工具 |
| `baseplate-bootstrap` | 启动入口、配置、静态资源 |

依赖方向：`bootstrap → starters → adapters → api`。禁止模块 import 另一个能力 starter 的 `Service`；跨模块能力走 `baseplate-api` 端口。

## MODULE LAYOUT
```
com.example.baseplate.<module>/
├── domain/                 领域对象与不变式（不依赖框架）
├── application/            用例编排、事务边界
├── adapter/in/web/         控制器、过滤器、拦截器
├── adapter/out/persistence/ DAO / Repository（显式 SQL）
├── config/                 @ConfigurationProperties
└── autoconfigure/          条件装配
```
新增模块清单字段参考 `baseplate-cend-starter/src/main/resources/META-INF/baseplate-module.yml`；权限码格式 `模块:动作`，与 `@RequiresPermission` 必须逐字符一致（拼错不报错，运行期 403）。

## WHERE TO LOOK
| Task | Location |
|------|----------|
| 架构/依赖规则/端口清单 | `docs/01-architecture.md` |
| 逐目录职责 | `docs/02-directory-map.md` |
| 新增业务模块完整模板 | `docs/05-business-module.md` |
| 安全模型（JWT/开放平台/数据权限） | `docs/06-security.md` |
| 编码规范与禁止项 | `docs/08-conventions.md` |
| 验证脚本说明 | `docs/09-testing.md` |
| 历史踩坑 | `docs/10-gotchas.md` |
| 智能体技能 | `skills/<名称>/SKILL.md` |

## COMMANDS
```powershell
mvn -pl baseplate-bootstrap -am -DskipTests clean package   # 完整后端构建
mvn test                                                     # TCK / ArchUnit（15 个测试文件，当前未定期跑）
java -jar baseplate-bootstrap/target/baseplate-bootstrap-1.0.0-SNAPSHOT.jar `
  --spring.flyway.enabled=true `
  --spring.flyway.locations=classpath:db/migration/baseplate,classpath:db/migration/identity,classpath:db/migration/audit,classpath:db/migration/platform,classpath:db/migration/metadata,classpath:db/migration/rule,classpath:db/migration/workflow,classpath:db/migration/agent,classpath:db/migration/cend
```

## CONVENTIONS
- 事务边界在 Service；只给写方法 `@Transactional`。
- `@AuditLog` 加在 Service，不加 Controller；访问审计由 `AccessLogFilter` 统一记。
- 异常映射集中在 `ApiExceptionHandler`：参数错 400、未认证 401、无权限 403、冲突 409、限流 429。
- 日志用 `{}` 占位符，ERROR 必带异常对象，禁止打印密码/令牌/密钥/完整手机号。
- SQL 参数与 `?` 逐位对齐；`TIMESTAMPTZ`；JSONB 输出前端前 `::text`。
- 迁移文件名 `V<yyyyMMddHHmmss>__<snake_case>.sql`，放所属模块 `src/main/resources/db/migration/<模块>/`。

## ANTI-PATTERNS
- `@ConditionalOnBean` 依赖注册顺序，易失效；优先 `@ConditionalOnClass`。
- 控制器类只写 `@RequestMapping` 不写 `@RestController` → Spring 7 下 404。
- 启动脚本 `--spring.flyway.locations` 漏目录 → 表不建但流程照跑，症状隐蔽。
- 打包前不停服务 → Windows 无法替换 jar。
- 关掉 `baseplate.registry-fail-fast` 来让启动通过。
- `baseplate-api` 引入 Spring / MyBatis / Jackson。
