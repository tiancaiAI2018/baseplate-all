# 02 · 目录与文件地图

## 本文档解决什么

「这个功能在哪个文件」「我要改的东西应该放哪」。INDEX 给的是目录树概览，这里给的是**每个包里有什么类、各自干什么**。

---

## 一、一次内网请求的完整路径

理解这条链，比记住任何目录都重要：

```
浏览器
  │  POST /api/identity/users  + Authorization: Bearer <jwt>
  ▼
TraceIdFilter               生成/透传 X-Trace-Id，写 MDC
  ▼
JwtAuthenticationFilter     验签 → 查黑名单 → 塞 SecurityContext + CurrentUserProviderImpl(ThreadLocal)
  ▼                            ├─ 失败：401，不往下走
MustChangePasswordFilter    首登未改密 → 403 PASSWORD_CHANGE_REQUIRED（只放行改密与登出）
  ▼
AccessLogFilter             记录方法/路径/耗时/IP/UA/Caller，请求结束写 aud_access_log
  ▼
RateLimitInterceptor        @RateLimit 才生效，Redis Lua 原子计数
IdempotencyInterceptor      @Idempotent 才生效，Idempotency-Key 换结果
OneTimeLinkInterceptor      @OneTimeLink 才生效，原子消费
  ▼
PermissionAspect            @RequiresPermission → 查 CurrentUser.permissions；SUPER_ADMIN 短路
  ▼
Controller                  参数绑定、校验，调 Service
  ▼
Service                     业务规则、事务边界
  ├─ CurrentUserProvider.current()   拿租户/用户
  ├─ DataScopeProvider              拿行级过滤条件
  ▼
Repository / Dao            显式 SQL（JdbcTemplate 或 MyBatis-Plus）
  ▼
PostgreSQL
```

写操作还会经过 `AuditAspect`（`@AuditLog`）在方法返回后异步写 `aud_business_log`，**含变更前后 JSON**。

---

## 二、后端逐模块

### `baseplate-api`（内核契约，59 个类）

```
annotation/
  RequiresPermission   权限点校验（值 = 权限编码数组）
  AuditLog             业务审计（action / resource）
  RateLimit            限流（key / permits / window）
  Idempotent           幂等（key）
  OneTimeLink          一次性链接（purpose）
  SignatureRequired    请求签名
  OpenApi              暴露到开放平台（code/name/subject/scope/permission）
exception/
  BaseplateException           基类，带 code
  AuthenticationException      → 401
  ForbiddenException           → 403
  RateLimitedException         → 429
model/
  CurrentUser          userId / tenantId / username / roles / permissions / mustChangePassword
  ModuleDescriptor     模块清单解析结果（14 个字段）
  MenuDef / PermissionDef / RuleFieldDef / OpenApiSubject
  DataScope / PageQuery / PageResult / AuthResult / Decision
  ProcessDefinitionView / ProcessInstanceView / TaskView
  Capability / DomainEvent / FormData / PortBinding / TenantId
port/out/               16 个出站端口，见 01-architecture.md
```

**新增注解放这里**，这样所有模块都能用。注意这个模块**不能加任何框架依赖**。

### `baseplate-security-starter`（鉴权链，18 个类）

```
jwt/
  JwtVerifier           HS256 验签 + claims 解析
  JwtClaims             声明模型
  TokenBlacklist        按 jti 吊销 + 按用户整体吊销（改密后旧令牌全失效）
web/
  JwtAuthenticationFilter       入口，见上面的请求链
  MustChangePasswordFilter      首登改密拦截
  TraceIdFilter                 traceId 生成与透传
  CachedResponseFilter          响应缓存（按需）
  CurrentUserProviderImpl       ★ ThreadLocal 实现，withUser/set/clear/get
  ApiExceptionHandler           RFC 9457 ProblemDetail，统一错误体
  RateLimitInterceptor / IdempotencyInterceptor / OneTimeLinkInterceptor
security/
  PermissionAspect      @RequiresPermission 的实现，SUPER_ADMIN 短路
  SecurityConfig        无状态过滤链，permitPaths 白名单
config/
  SecurityProperties    jwt-secret / token-ttl / permit-paths / trace-header
```

**要改放行路径**（比如新增静态资源）：`SecurityProperties.permitPaths` + `application.yml`。
不要直接改 `SecurityConfig` 里的 `permitAll()` 列表。

### `baseplate-identity-starter`（23 个类）

```
application/
  AuthenticationService   登录：锁定判断 → 密码校验 → 失败计数 → 签发 JWT → 写登录日志
  PasswordService         改密：策略校验 → 历史比对 → 更新 → 吊销该用户全部令牌
  UserAdminService        用户 CRUD、重置密码（返回一次性明文）
  RoleService             角色 CRUD、分配权限
  OrgService              组织树
  AdminSeeder             首次启动建 admin + SUPER_ADMIN
  IdentityUserDirectory   ★ 实现 api 的 UserDirectory，供开放平台代用户调用
  DefaultDataScopeProvider  数据范围实现
domain/
  PasswordPolicy          长度/字符类/用户名包含/历史次数
  PasswordHasher          BCrypt
adapter/in/web/
  AuthController          /api/auth/{login,logout,me,password}
  UserAdminController     /api/identity/users/**
  RoleController          /api/identity/roles/**
  OrgController           /api/identity/orgs/**
  PermissionController    /api/identity/permissions/**、/menus、/modules/{id}
adapter/out/persistence/
  IdentityDao                   全部显式 SQL
  PermissionCatalogRepository   sys_permission / sys_module 同步与维护
config/IdentityProperties        密码策略、锁定策略、默认租户
```

### `baseplate-audit-starter`（5 个类）

```
application/AuditWriter                异步有界队列（满了丢日志，不拖慢请求）
adapter/in/web/AccessLogFilter         访问审计（含第三方应用归属）
adapter/in/web/AuditAspect             @AuditLog 的业务审计，参数脱敏
adapter/in/web/AuditQueryController    三类列表 + 两个详情接口
```

### `baseplate-platform-starter`（21 个类，最复杂）

```
adapter/in/web/
  OpenApiAuthFilter            ★ /openapi/** 独立鉴权链。顺序：验签→时间窗→nonce→目录→授权→令牌→主体→scope→用户权限→配额→沙盒
  OpenApiAnnotationScanner     ★ 启动期扫 @OpenApi，校验路径前缀/单路径/单方法/编码唯一
  OpenApiController            /openapi/v1/**：token、ping、whoami、userinfo、echo
  OpenOAuthAuthorizeController /api/platform/oauth/**：授权页上下文、发码、授权清单、撤回
  PlatformConsoleController    /api/platform/apps/**：应用 CRUD、密钥轮换、授权、日志、用量、沙盒
  PlatformApiCatalogController /api/platform/apis/**：目录查询、手工登记、改名、启停、删除
  CachedBodyRequest            缓存请求体（验签要读两次）
  OpenApiFailure / OAuthError  两种错误模型
adapter/out/persistence/
  OpenAppRepository            应用与密钥（密文存储）
  OpenApiCatalogRepository     open_api 目录 + open_app_api 授权
  OpenAuthCodeRepository       授权码，原子消费
  OpenRefreshTokenRepository   刷新令牌，只存 SHA-256，轮换
application/OpenAppService     应用管理与授权校验（含「没开通授权码不许勾 USER 接口」）
token/OpenTokenService         开放令牌签发与校验，claims 带 uid/uname
signature/OpenApiSignature     待签串拼装与 HMAC 校验
crypto/SecretCipher            AES-GCM
config/PlatformProperties      token-secret / secret-key / ttl / 窗口 / 沙盒前缀
```

**三个最容易被改坏的地方**：`OpenApiAuthFilter` 的校验顺序、`OpenApiAnnotationScanner` 的路径校验、
`OpenTokenService` 的密钥来源。改之前先看 [10-gotchas.md](10-gotchas.md) 的「安全」小节。

### 其余能力模块

| 模块 | 关键类 | 说明 |
|---|---|---|
| `workflow` | `FlowableProcessEngineAdapter`、`BpmnXmlValidator`、`NodeConfigCompiler`、`BpmnDraftComposer`、`WorkflowController`、`WorkflowDraftController`、`DispatchController`、`WorkflowDispatchService`、`AgentOnlineRedispatchListener` | BPMN 部署用 `disableSchemaValidation()`（fat jar 下 XSD 解析失败）；XML 走白名单校验；节点配置与草稿由服务端编译；分单只从池组织解析候选，扩展点走 `baseplate-api` 端口；签到上线发 `AgentOnlineEvent`（AFTER_COMMIT）实时重派待派任务，定时扫描兜底 |
| `rule` | `StructuredRuleInterpreter`、`RuleFieldRegistry`、`RuleController` | 只有「字段 + 运算符 + 值」，不接受表达式文本 |
| `metadata` | `MetadataService`、`MetadataController` | FORM/LIST/FLOW 三类，字段白名单，禁 `script` / `expression`；`suspend/activate` 只挡新绑定 |
| `registry` | `ModuleDescriptorLoader`、`ModuleDescriptorValidator`、`MigrationPlanWriter` | 清单加载与校验 |
| `runtime` | `PortRegistry`、`EncryptedPropertyEnvironmentPostProcessor`、`TenantContext` | 端口唯一性、配置解密 |
| `adapters` | `redis/*`、`memory/*` | 每个端口两套实现 |
| `agent` | `AgentChatService`、`AgentConfigService`、`AgentRuntimeFactory`、`ToolRegistry`、`ToolDirectory`、`ToolCliTool`、`SkillIndexService` | 智能体定义/会话/工具/技能/HITL/外部调用；内部工具经 `baseplate` CLI 按需检索执行；只认工具接口，不认识具体业务 |
| `agent-workflow` | `WorkflowAgentTools`、`WorkflowAgentToolProvider`、`FormAgentTools`、`FormAgentToolProvider` | 业务工具声明；内部只读，写操作声明为外部工具交给前端执行 |

### `baseplate-bootstrap`（唯一可执行制品）

```
BootstrapApplication             启动类
ModuleRegistryPersistenceRunner  模块状态写 bp_module_registry
PortsEndpoint                    /actuator/ports 查端口绑定
resources/application.yml        主配置
resources/static/                前端构建产物（npm run build 后同步进来）
```

---

## 三、前端目录

```
baseplate-console/src/
  main.ts                 挂载 Vue、注册 Element Plus
  App.vue                 根组件
  style.css               ★ 全局设计系统：CSS 变量 + .page/.card/.toolbar/.perm-group/.kv/.json 等通用类
  api/client.ts           ★ 唯一的 axios 实例。令牌注入、401 跳登录、403 PASSWORD_CHANGE_REQUIRED 跳改密
  api/agentStream.ts      对话 / HITL / 外部工具回传的 SSE 解析（POST + fetch，不用 EventSource）
  api/agentExternal.ts    外部工具前端执行器注册表（toolCode → handler）
  api/agentCanvas.ts      页面画布读取器注册表（设计器挂载时注册，卸载时注销）
  stores/auth.ts          ★ 登录态、has(permission)、isSuperAdmin、loadMe
  stores/agent.ts         ★ 会话、消息块、外部工具结果排队、HITL 决策、流式状态
  stores/workflow.ts      待办角标
  router/index.ts         ★ 路由表 + 守卫（未登录带 redirect 跳登录；首登未改密强制去改密页）
  layout/ConsoleLayout.vue  侧边栏（按 auth.has 控制菜单可见）+ 顶栏面包屑 + 悬浮助手
  components/agent/       AgentAssistant（气泡/面板）、AgentMarkdown、AgentBpmnCard
                          formRuleBuilder（AI 字段 → form-create）、SchemaFieldEditor
  utils/bpmnLayout.ts     dagre 自动排版
  utils/bpmnSpec.ts       画布 → 节点/连线 spec（供 AI 读取当前画布）
  views/                  22 个页面，一页一个 .vue
```

### 页面清单

| 文件 | 路由 | 说明 |
|---|---|---|
| `LoginView.vue` | `/login` | 左右分栏；支持 `?redirect=` 登录后回跳（开放平台授权用） |
| `ChangePasswordView.vue` | `/change-password` | 首登/过期强制来这 |
| `OAuthAuthorizeView.vue` | `/oauth/authorize` | **独立全屏**，不在 ConsoleLayout 里 |
| `DashboardView.vue` | `/dashboard` | 统计卡 + 最近访问 + 已启用模块 |
| `UsersView.vue` | `/identity/users` | 含重置密码一次性明文展示 |
| `RolesView.vue` | `/identity/roles` | 权限树 + 「权限点管理」抽屉（新增/改名/启停/删除 + 模块改名） |
| `OrgsView.vue` | `/identity/orgs` | 组织树 |
| `OpenAppsView.vue` | `/platform/apps` | 应用列表 + 900px 详情抽屉（6 个 tab，含授权树与对接说明） |
| `ApiCatalogView.vue` | `/platform/apis` | 接口目录：统计、筛选、启停、改名、手工登记 |
| `AuditView.vue` | `/audit/logs` | 三类 tab + 详情抽屉 |
| `RulesView.vue` | `/rule/config` | 规则集配置与试算 |
| `MetadataView.vue` | `/metadata/forms` | 低代码 Schema 编辑 |
| `WorkflowView.vue` | `/workflow/designer` | 5 个 tab：设计器 / 定义 / 发起 / 实例 / 待办；画布向 AI 注册读取器 |
| `NodeConfigPanel.vue` | — | 设计器右侧属性面板（找人/签署/表单/条件/按钮/分单策略） |
| `WorkflowLabView.vue` | `/workflow/lab` | 演练环境：初始化、账号、演示流程 |
| `DispatchCenterView.vue` | `/workflow/dispatch` | 签到、抢单大厅、我的分单待办、待评价；指派/拒绝/评价弹窗 |
| `DispatchPoolView.vue` | `/workflow/dispatch/pools` | 分单池与组织范围配置（含下级开关） |
| `DispatchBoardView.vue` | `/workflow/dispatch/board` | 分单看板：决策日志、拒绝、惩罚撤销、评价 |
| `ProcessStartView.vue` | `/workflow/start` | 发起流程：选定义、填表单、提交 |
| `MyTodoView.vue` | `/workflow/todo` | 待办/已办；支持 AI 卡片深链 `?taskId=` 直接打开办理抽屉 |
| `MyInstancesView.vue` | `/workflow/mine` | 我发起的实例与进度 |
| `FormListView.vue` | `/workflow/forms` | 流程表单列表：详情、编辑、作废/恢复、新增 |
| `FormDesignerView.vue` | `/workflow/forms/design` | 表单设计器；向 AI 注册当前字段读取器 |
| `FormRenderer.vue` | — | form-create 渲染/预览 |
| `AgentConfigView.vue` | `/agent/config` | 智能体配置：模型、提示词、页面、工具、技能、测试连接 |

---

## 四、按功能找文件

| 我要改…… | 文件 |
|---|---|
| 登录失败锁定次数 | `application.yml` → `baseplate.identity.max-failed-attempts` |
| 密码复杂度要求 | `baseplate-identity-starter/.../domain/PasswordPolicy` |
| 哪些路径不需要登录 | `SecurityProperties.permitPaths` + `application.yml` |
| 错误响应格式 | `baseplate-security-starter/.../web/ApiExceptionHandler` |
| 审计记录哪些字段 | `AuditWriter.access(...)` 签名（加字段要同步改表和前端详情抽屉） |
| 第三方请求在审计里显示谁 | `AccessLogFilter` 读 `openapi.appName` / `openapi.actUser` 属性 |
| 开放接口的鉴权顺序 | `OpenApiAuthFilter.doFilterInternal` |
| 哪些接口能暴露到开放平台 | 业务方法上的 `@OpenApi`；能不能勾选还看 `open_app.grant_types` |
| 令牌有效期 | `baseplate.platform.token-ttl`（开放）、`baseplate.identity.token-ttl`（内部） |
| 流程能部署什么 BPMN | `BpmnXmlValidator` 的白名单 |
| 流程节点的条件/按钮怎么编译 | `NodeConfigCompiler`；AI 草稿由 `BpmnDraftComposer` |
| 规则能用哪些字段 | 各模块清单的 `rule-fields`，汇总在 `RuleFieldRegistry` |
| 智能体怎么按页面选工具 | `AgentChatService.selectTools` + `ToolDescriptor.pages` |
| 外部工具怎么执行 | `baseplate-console/src/api/agentExternal.ts` 注册 handler；服务端只发 schema |
| AI 怎么读当前画布 | 设计器页 `registerAgentCanvas` + `utils/bpmnSpec.ts` |
| AI 消息卡片怎么渲染 | `AgentAssistant.vue` 的 `ui` 分支 + `AgentBpmnCard.vue` |
| 智能体系统提示词 | `AgentRuntimeFactory.systemPrompt`（不再拼工具列表） |
| 前端主题色/圆角 | `baseplate-console/src/style.css` 顶部的 `:root` 变量 |
| 侧边栏菜单分组 | `baseplate-console/src/layout/ConsoleLayout.vue` 的 `el-menu-item-group` |

---

## 五、不该放东西的地方

| 位置 | 原因 |
|---|---|
| `baseplate-application/` | 预留位，只有 `package-info`。放业务代码等于绕过分层 |
| `baseplate-api/` 里加 Spring 依赖 | 触发 `PomBoundaryTest` 失败，且内核不可移植 |
| `baseplate-bootstrap/` 里写业务逻辑 | 它只负责装配与启动 |
| `baseplate-console/src/views/` 里直接写 `axios` | 必须走 `api/client.ts`，否则令牌注入与 401 处理失效 |
| 内部工具里直接改业务数据 | 权限、审计、数据归属会被模型绕过；写操作必须声明为外部工具走 REST |
| `AgentRuntimeFactory` 里拼「可用工具」列表 | 工具集变化会让 prompt 前缀失效，破坏厂商侧缓存；schema 已经暴露给模型 |
| `baseplate-bootstrap/src/main/resources/static/` 手改 | 这是前端构建产物的同步目标，改了下次 `npm run build` 就丢 |
