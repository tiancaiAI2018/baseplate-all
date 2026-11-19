# 文档索引

这是基盘文档的入口。**先看这一页，再决定读哪一篇**，不要按序号从头读到尾。

---

## 一、按任务找文档

| 我要做的事 | 读这篇 | 关键章节 |
|---|---|---|
| 本地把服务跑起来（Windows 一键） | [03-getting-started.md](03-getting-started.md) | 「Windows 一键启动」 |
| **加一个新的业务模块**（有表、有菜单、有权限、有接口） | [05-business-module.md](05-business-module.md) | 全部，含完整模板 |
| 加一个内网接口并控制权限 | [05-business-module.md](05-business-module.md) → [08-conventions.md](08-conventions.md) | 「控制器与服务」 |
| 把接口开放给第三方 | [06-security.md](06-security.md) | 「开放平台」 |
| 查某个端点要什么权限 | [04-api.md](04-api.md) | 按模块分组的表 |
| 加一个前端页面 | [07-frontend.md](07-frontend.md) | 「新增页面步骤」 |
| **给 AI 助手加一个工具 / 技能 / 页面** | [01-architecture.md](01-architecture.md) → [04-api.md](04-api.md) → [07-frontend.md](07-frontend.md) | 「AI 编排层」「智能体」「AI 助手」 |
| 改了功能要回归验证 | [09-testing.md](09-testing.md) | 脚本与智能体验收 |
| 排查启动失败 / 诡异现象 | [03-getting-started.md](03-getting-started.md) → [10-gotchas.md](10-gotchas.md) | 排错清单 |
| 想知道某个目录/文件是干什么的 | [02-directory-map.md](02-directory-map.md) | 全部 |
| 判断能不能这样改（依赖、分层） | [01-architecture.md](01-architecture.md) | 「依赖规则」 |

---

## 二、AI 阅读顺序

首次在这个仓库里干活，按下面顺序读，总量约 40 分钟，能覆盖 90% 的场景。

### 场景 A：要改后端业务逻辑

1. `../README.md` — 五个不能碰的约束（必须先看）
2. `01-architecture.md` — 分层是什么、能依赖谁
3. `05-business-module.md` — 代码放哪、清单怎么写
4. `08-conventions.md` — 命名、SQL、异常
5. `10-gotchas.md` — 动手前扫一眼，能少踩一半坑

### 场景 B：要改前端页面

1. `07-frontend.md` — 目录、API 层、样式系统
2. `10-gotchas.md` 的「前端」小节 — Element Plus 的坑
3. `04-api.md` — 找对应的后端端点

### 场景 C：要改安全 / 权限 / 开放平台

1. `06-security.md` — 令牌模型、权限模型、主体类型
2. `01-architecture.md` 的「端口清单」— 哪些能力是端口
3. `10-gotchas.md` 的「安全」小节 — 历史上出过的事故

### 场景 D：要改智能体 / AI 助手

1. `01-architecture.md` 的「AI 编排层」— 模块边界、工具分类、页面作用域
2. `04-api.md` 的「智能体 `/api/agent`」— 对话 SSE、HITL、外部工具回传
3. `07-frontend.md` 的「AI 助手」— 外部工具注册表、画布读取、UI 卡片
4. `10-gotchas.md` 的「AI 与智能体」小节 — 恢复流、缓存、卡片渲染的坑

### 场景 E：只想知道现状

读 `../../README.md` 的「现状」表和 [`../../../Baseplate基盘_实现与验证报告.html`](../../../Baseplate基盘_实现与验证报告.html)。

---

## 三、完整目录树

```
baseplate/                                  ← 后端根（Maven 多模块）
├── pom.xml                                 ← 父 POM：模块列表、版本、依赖管理
├── run-server.sh                           ← 本机启动脚本（读 ../村通/.../.env）
├── README.md                               ← 项目入口
├── docs/                                   ← 本文档目录
│   ├── INDEX.md                            ← 本文件
│   ├── 01-architecture.md
│   ├── 02-directory-map.md
│   ├── 03-getting-started.md
│   ├── 04-api.md
│   ├── 05-business-module.md
│   ├── 06-security.md
│   ├── 07-frontend.md
│   ├── 08-conventions.md
│   ├── 09-testing.md
│   └── 10-gotchas.md
│
├── baseplate-api/                          ← 内核契约。零业务、零 Web 依赖
│   └── src/main/java/com/example/baseplate/api/
│       ├── annotation/                     ← @RequiresPermission / @AuditLog / @RateLimit
│       │                                      @Idempotent / @OneTimeLink / @SignatureRequired / @OpenApi
│       ├── exception/                      ← BaseplateException / AuthenticationException / ForbiddenException
│       ├── model/                          ← CurrentUser / ModuleDescriptor / MenuDef / PermissionDef
│       │                                      OpenApiSubject / RuleFieldDef / DataScope / PageResult ...
│       └── port/out/                       ← 16 个出站端口（详见 01-architecture.md）
│
├── baseplate-domain/                       ← 共享领域原语（当前只有 AggregateRoot）
├── baseplate-application/                  ← 预留扩展位（只有 package-info，无代码）
│
├── baseplate-runtime/                      ← 运行时装配
│   └── .../runtime/
│       ├── PortRegistry                    ← 每个端口唯一 Bean，重复即失败
│       ├── EncryptedPropertyEnvironmentPostProcessor  ← 配置项解密（EN C[...] 语法）
│       ├── TenantContext                   ← 租户上下文
│       └── BaseplateProperties             ← baseplate.* 配置绑定
│
├── baseplate-registry/                     ← 模块清单机制
│   └── .../registry/
│       ├── ModuleDescriptorLoader          ← 跨 jar 扫 META-INF/baseplate-module.yml
│       ├── ModuleDescriptorValidator       ← 字段与依赖校验（fail-fast）
│       └── MigrationPlanWriter             ← 生成 build/migration-plan.json
│
├── baseplate-starter/                      ← 聚合 starter，业务方引这个
│   └── src/main/resources/
│       ├── application-template.yml        ← 配置模板
│       └── META-INF/spring/...AutoConfiguration.imports
│
├── baseplate-contract/                     ← 端口契约与边界守卫
│   └── src/
│       ├── main/java/.../contract/tck/     ← 6 个端口的 TCK（KeyValueStore/RateLimiter/...）
│       └── test/java/.../contract/arch/
│           ├── PomBoundaryTest             ← 依赖方向守卫
│           └── ReverseCompileProbeTest     ← 反向编译探针
│
├── baseplate-adapters/                     ← 基础设施实现
│   └── src/main/
│       ├── java/.../adapters/
│       │   ├── redis/                      ← 6 件套：KeyValue/Idempotency/Nonce/OneTimeToken/RateLimiter/Cache
│       │   ├── memory/                     ← 同 6 件的内存降级实现
│       │   └── mybatis/                    ← MyBatis-Plus 配置
│       └── resources/db/migration/baseplate/
│           ├── V20261110100000__bp_module_registry.sql
│           └── V20261110100100__core_tables.sql
│
├── baseplate-security-starter/             ← 鉴权链
│   └── src/main/java/.../security/
│       ├── jwt/                            ← JwtVerifier / JwtClaims / TokenBlacklist
│       ├── web/                            ← JwtAuthenticationFilter / MustChangePasswordFilter
│       │                                      TraceIdFilter / CachedResponseFilter / ApiExceptionHandler
│       │                                      RateLimitInterceptor / IdempotencyInterceptor
│       │                                      OneTimeLinkInterceptor / CurrentUserProviderImpl
│       ├── security/                       ← PermissionAspect / SecurityConfig
│       ├── config/                         ← SecurityProperties
│       └── autoconfigure/SecurityAutoConfiguration
│
├── baseplate-identity-starter/             ← 身份与权限
│   └── src/main/
│       ├── java/.../identity/
│       │   ├── application/                ← AuthenticationService / PasswordService / UserAdminService
│       │   │                                  RoleService / OrgService / AdminSeeder / IdentityUserDirectory
│       │   ├── adapter/in/web/             ← AuthController / UserAdminController / RoleController
│       │   │                                  OrgController / PermissionController
│       │   ├── adapter/out/persistence/    ← IdentityDao / PermissionCatalogRepository / UserRepository
│       │   ├── domain/                     ← PasswordPolicy / PasswordHasher
│       │   └── config/                     ← IdentityProperties
│       └── resources/
│           ├── META-INF/baseplate-module.yml   ← 模块清单（权限点/菜单/数据范围/规则字段）
│           └── db/migration/identity/
│
├── baseplate-audit-starter/                ← 审计
│   └── .../audit/
│       ├── application/AuditWriter         ← 异步有界队列
│       ├── adapter/in/web/                 ← AccessLogFilter / AuditAspect / AuditQueryController
│       └── resources/db/migration/audit/
│
├── baseplate-platform-starter/             ← 开放平台
│   └── .../platform/
│       ├── adapter/in/web/                 ← OpenApiController / OpenApiAuthFilter
│       │                                      OpenApiAnnotationScanner / OpenOAuthAuthorizeController
│       │                                      PlatformConsoleController / PlatformApiCatalogController
│       ├── adapter/out/persistence/        ← OpenAppRepository / OpenApiCatalogRepository
│       │                                      OpenAuthCodeRepository / OpenRefreshTokenRepository
│       ├── application/OpenAppService
│       ├── token/OpenTokenService          ← 开放令牌（与内部 JWT 不同密钥）
│       ├── signature/OpenApiSignature      ← HMAC-SHA256 待签串
│       ├── crypto/SecretCipher             ← AES-GCM 加密库里的应用密钥
│       └── resources/db/migration/platform/
│
├── baseplate-workflow-starter/             ← Flowable 8 + 流程草稿
│   └── .../workflow/
│       ├── adapter/out/flowable/
│       │   ├── FlowableProcessEngineAdapter ← 部署/发起/办理/驳回/打回
│       │   ├── NodeConfigCompiler           ← 结构化节点配置 → BPMN 片段
│       │   └── BpmnXmlValidator             ← 白名单校验（禁脚本条件）
│       ├── adapter/in/web/WorkflowController
│       ├── adapter/in/web/WorkflowDraftController  ← 草稿 CRUD
│       └── application/BpmnDraftComposer    ← AI 节点/连线 → BPMN + 自动排版
│
├── baseplate-rule-starter/                 ← 结构化规则引擎
│   └── .../rule/
│       ├── StructuredRuleInterpreter       ← 字段+运算符+值，无表达式文本
│       ├── RuleFieldRegistry               ← 字段目录来自模块清单
│       └── adapter/in/web/RuleController
│
├── baseplate-metadata-starter/             ← 低代码元数据
│   └── .../metadata/
│       ├── MetadataService                 ← FORM/LIST/FLOW 三类，字段白名单 + 作废标记
│       └── adapter/in/web/MetadataController
│
├── baseplate-agent-starter/                ← 智能体平台
│   ├── domain/                             ← AgentDefinition / ApprovalMode / ToolType / RiskLevel
│   ├── application/                        ← AgentChatService（SSE 编排）/ AgentConfigService
│   │                                          AgentSessionService / SkillIndexService
│   ├── runtime/                            ← AgentRuntimeFactory / AgentEventMapper / SseEventWriter
│   ├── model/                              ← ModelProviderFactory（OpenAI 兼容端点）
│   ├── tool/                               ← ToolRegistry / ToolDescriptor / PlatformTools / HttpApiTool
│   ├── adapter/in/web/                     ← Chat / Config / Session / Hitl / ExternalCall /
│   │                                          Skill / ToolDirectory 七组控制器
│   ├── adapter/out/persistence/            ← 定义/会话/消息/审批/外部调用/技能索引仓储
│   └── resources/db/migration/agent/       ← agent_* 10 张表
│
├── baseplate-agent-workflow-starter/       ← 智能体的工作流与表单工具声明
│   └── .../agent/workflow/
│       ├── WorkflowAgentTools              ← 待办/任务详情/流程定义（内部，只读）
│       ├── WorkflowAgentToolProvider       ← 8 个工作流工具声明
│       ├── FormAgentTools                  ← 表单列表/详情（内部，只读）
│       └── FormAgentToolProvider           ← 4 个表单工具声明
│
└── baseplate-bootstrap/                    ← 唯一可执行制品
    └── src/main/
        ├── java/.../bootstrap/
        │   ├── BootstrapApplication
        │   ├── ModuleRegistryPersistenceRunner
        │   └── PortsEndpoint               ← /actuator/ports
        └── resources/
            ├── application.yml
            └── static/                     ← 前端构建产物（由 npm run build 同步进来）

baseplate-console/                          ← 前端（与 baseplate/ 同级）
├── package.json
├── vite.config.ts
├── index.html
└── src/
    ├── main.ts
    ├── App.vue
    ├── style.css                           ← 全局设计系统（CSS 变量 + 通用类）
    ├── api/client.ts                       ← axios 实例、令牌注入、401/403 拦截
    ├── api/agentStream.ts                  ← 对话 / HITL / 外部工具回传的 SSE 解析
    ├── api/agentExternal.ts                ← 外部工具前端执行器注册表
    ├── api/agentCanvas.ts                  ← 页面画布读取器注册表（设计器当前状态）
    ├── stores/auth.ts                      ← 登录态、has(permission)、isSuperAdmin
    ├── stores/agent.ts                     ← 会话、消息块、外部工具排队、HITL 决策
    ├── stores/workflow.ts                  ← 待办角标
    ├── router/index.ts                     ← 路由表 + 登录守卫 + 首登改密拦截
    ├── layout/ConsoleLayout.vue            ← 侧边栏 + 顶栏 + 悬浮助手
    ├── components/agent/                   ← AgentAssistant / AgentMarkdown / AgentBpmnCard
    │                                          formRuleBuilder / schemaField / SchemaFieldEditor
    ├── utils/bpmnLayout.ts                 ← dagre 自动排版
    ├── utils/bpmnSpec.ts                   ← 画布 → 节点/连线 spec
    └── views/                              ← 22 个页面
        ├── LoginView.vue / ChangePasswordView.vue / OAuthAuthorizeView.vue
        ├── DashboardView.vue / AuditView.vue / RulesView.vue / MetadataView.vue
        ├── UsersView.vue / RolesView.vue / OrgsView.vue
        ├── OpenAppsView.vue / ApiCatalogView.vue
        ├── WorkflowView.vue / NodeConfigPanel.vue / WorkflowLabView.vue
        ├── ProcessStartView.vue / MyTodoView.vue / MyInstancesView.vue
        ├── FormListView.vue / FormDesignerView.vue / FormRenderer.vue
        └── AgentConfigView.vue
```

---

## 四、关键文件速查

| 我要改…… | 去这个文件 |
|---|---|
| 某个接口要什么权限 | 控制器方法上的 `@RequiresPermission("x:y")`，权限点本身在模块清单 |
| 新增一个权限点 | `baseplate-identity-starter/src/main/resources/META-INF/baseplate-module.yml` 的 `permissions` |
| 新增一个菜单项 | 同上 `menus`；前端路由 `baseplate-console/src/router/index.ts`；侧栏 `ConsoleLayout.vue` |
| 加一张表 | 所属模块的 `src/main/resources/db/migration/<模块>/V<时间戳>__说明.sql` |
| 加一个端口 | `baseplate-api/.../port/out/` 新增接口 + 在 `baseplate-adapters` 给实现 |
| 改令牌有效期 | `application.yml` 的 `baseplate.platform.token-ttl` / `identity.*` |
| 改密码策略 | `baseplate-identity-starter/.../config/IdentityProperties` + `PasswordPolicy` |
| 改登录锁定策略 | `application.yml` 的 `baseplate.identity.max-failed-attempts` / `lock-minutes` |
| 改开放平台主体规则 | `baseplate-platform-starter/.../adapter/in/web/OpenApiAuthFilter` |
| 改前端主题色 | `baseplate-console/src/style.css` 顶部的 CSS 变量 |
| 改审计保留策略 | `baseplate-audit-starter/.../AuditWriter`（队列容量）+ 表清理脚本 |
| 改前端页面标题/菜单分组 | `baseplate-console/src/layout/ConsoleLayout.vue` |
| 加一个智能体工具 | `baseplate-agent-workflow-starter/.../*ToolProvider`（声明）+ `*Tools`（内部实现）+ `baseplate-console/src/api/agentExternal.ts`（外部执行器） |
| 改智能体提示词 / 页面绑定 | `baseplate-agent-starter/.../runtime/AgentRuntimeFactory` + 控制台「智能体配置」页 |
| 改 AI 消息卡片 | `baseplate-console/src/components/agent/AgentAssistant.vue`（`ui` 分支）+ `AgentBpmnCard.vue` |
| 改待办 / 办理工具 | `WorkflowAgentTools`（内部查询）+ `WorkflowAgentToolProvider`（外部办理） |
| 改画布读取范围 | 设计器页的 `registerAgentCanvas` + `baseplate-console/src/api/agentCanvas.ts` |
| 改 Windows 启动方式 | 根目录 `start-*.cmd` + `scripts/dev-server.ps1` |

---

## 五、术语

| 术语 | 含义 |
|---|---|
| **基盘** | 本项目的自称：横向通用能力层 |
| **模块清单** | 每个能力 starter 里 `META-INF/baseplate-module.yml`，声明权限点、菜单、数据范围、Flyway 位置 |
| **端口（Port）** | `baseplate-api` 里的出站接口，如 `RateLimiter`，业务只依赖接口 |
| **适配器（Adapter）** | 端口的具体实现，如 Redis 版与内存版 `RateLimiter` |
| **能力 starter** | `baseplate-*-starter`，引进来就带一组能力 + 一张或多张表 |
| **主体（Subject）** | 开放接口的调用者身份：`CLIENT` 机器 / `USER` 用户 / `ANY` 两者 |
| **数据范围（DataScope）** | 行级数据权限，显式下传，不做隐式织入 |
| **首登改密** | 管理员初始化或重置密码后，用户首次登录必须改密才能做别的 |
| **沙盒** | 开放平台独立租户前缀 `sandbox:`，不写生产数据 |
| **智能体（Agent）** | 绑定在页面上、带模型与工具集的助手；定义、工具、技能、会话都落库 |
| **内部工具** | 服务端直接执行、只读查询，按用户权限点过滤 |
| **外部工具** | 前端执行：服务端只下发 schema 与参数，浏览器带用户令牌调业务接口 |
| **技能（Skill）** | `baseplate/skills/<名称>/SKILL.md`，按 Agent 绑定后作为提示词/能力扩展 |
| **页面作用域（pageKey）** | 工具、Agent、会话都按页面过滤；换页继续旧会话时要同步 `session.page_key` |
| **HITL** | Human-in-the-loop：审批策略为 `ASK` 的工具先挂起，用户同意后恢复同一会话 |
| **UI 卡片** | 工具结果里的 `ui` 指令，经 `ui` SSE 事件交给前端渲染（任务列表 / 流程图缩略图） |
| **流程草稿** | `wf_process_draft`：AI 或设计器保存的节点/连线，未部署，可再次打开修改 |

---

## 六、文档维护约定

改了下面这些，**必须同步改对应 md**，否则文档就是负债：

| 代码变更 | 要改的文档 |
|---|---|
| 新增/删除 REST 端点 | `04-api.md` |
| 新增/修改智能体工具 | `../README.md` 工具表 + `04-api.md` + `07-frontend.md`（外部执行器/卡片） |
| 新增智能体端点或 SSE 事件 | `04-api.md` 的「智能体」章节 |
| 新增 agent 相关环境变量 | `03-getting-started.md`（配置表）+ `../README.md`（启动命令） |
| 模块清单新增字段 | `01-architecture.md`（清单字段表）+ `05-business-module.md`（模板） |
| 新增端口或适配器 | `01-architecture.md`（端口清单） |
| 新增环境变量 | `03-getting-started.md`（配置表）+ `../README.md`（启动命令） |
| 新增权限点 | `04-api.md` 对应行的权限列 |
| 新增验证脚本断言 | `09-testing.md`（脚本说明与数字） |
| 修掉一个会导致「看起来正常其实错了」的坑 | `10-gotchas.md` |

文档里的数字（模块数、端点数、表数、断言数）**以实际命令输出为准**，不要凭记忆写。
