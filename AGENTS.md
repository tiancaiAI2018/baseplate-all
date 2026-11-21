# PROJECT KNOWLEDGE BASE

**Generated:** 2026-11-21
**Commit:** 424d57d
**Branch:** main

## OVERVIEW
Baseplate 可复用业务基盘：Java 21 / Spring Boot 4.1.2 多模块后端 + Vue 3 控制台。把身份权限、审计、开放平台、工作流、规则、低代码元数据、AI 编排沉淀为平台层，业务模块以 Maven 依赖方式接入。

## STRUCTURE
```
baseplate-all/
├── baseplate/                     # 后端 Maven 多模块（19 模块，唯一可执行制品 = baseplate-bootstrap）
│   ├── baseplate-api/             # 内核契约：端口/模型/注解/异常，零框架依赖
│   ├── baseplate-*-starter/       # 能力模块：identity/audit/platform/workflow/rule/metadata/agent/cend...
│   ├── baseplate-adapters/        # MyBatis-Plus / Flyway / Redis 实现 + 内存降级
│   ├── baseplate-contract/        # 端口 TCK + ArchUnit 边界测试
│   ├── baseplate-runtime/         # 端口注册表、配置解密、租户上下文
│   ├── baseplate-registry/        # 模块清单解析/校验/迁移计划
│   ├── baseplate-bootstrap/       # 启动入口 + application.yml + 内嵌前端静态资源
│   ├── docs/                      # 01~10 权威文档（改行为必须同步）
│   └── skills/                    # 智能体技能 SKILL.md
├── baseplate-console/             # 前端控制台 Vue3 + Vite + Element Plus + tmagic + bpmn-js
├── scripts/                       # dev-server.ps1 / dev-common.ps1 / build-backend.ps1
├── start-*.cmd, build-backend.cmd # 仅 ASCII；中文提示必须放 .ps1
└── .env                           # 本地数据库/Redis/密钥/模型配置（gitignored）
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| 后端总览与模块职责 | `baseplate/README.md`、`baseplate/docs/01-architecture.md` | 先读 README 的「六条红线」 |
| 加业务模块 | `baseplate/docs/05-business-module.md` | 含建表/权限/审计/接口模板 |
| 编码规范 | `baseplate/docs/08-conventions.md` | 分层、SQL、异常、DTO |
| 已踩的坑 | `baseplate/docs/10-gotchas.md` | 动手前扫一遍 |
| 端点与权限 | `baseplate/docs/04-api.md` | 改路由/权限必须同步 |
| 前端目录与 API 层 | `baseplate/docs/07-frontend.md` | 新增页面步骤 |
| 启动/构建 | `scripts/dev-server.ps1`、`scripts/build-backend.ps1` | .env 只注入当前进程 |
| 模块清单元数据 | `baseplate/*/src/main/resources/META-INF/baseplate-module.yml` | 权限点/菜单/Flyway 目录 |

## CODE MAP
| Symbol | Type | Location | Role |
|--------|------|----------|------|
| `BootstrapApplication` | class | `baseplate/baseplate-bootstrap/src/main/java/com/example/baseplate/BootstrapApplication.java` | 唯一启动入口 |
| `ModuleDescriptor` | record | `baseplate/baseplate-api/.../api/model/ModuleDescriptor.java` | 模块清单模型 |
| `CurrentUser` / `CurrentUserProvider` | record/port | `baseplate/baseplate-api/.../api/model`、`api/port/out` | 身份与租户来源 |
| `AgentRuntimeFactory` | class | `baseplate/baseplate-agent-starter/.../agent/runtime/` | 智能体装配与 system prompt |
| `ToolRegistry` / `ToolDescriptor` | class/record | `baseplate/baseplate-agent-starter/.../agent/tool/` | 工具注册与页面/权限作用域 |
| `client.ts` | module | `baseplate-console/src/api/client.ts` | 前端唯一 axios 实例 |

## COMMANDS
```powershell
# Windows 一键（前后端各开一个窗口，Ctrl+C 即停）
start-all.cmd
start-backend.cmd      # :8080
start-frontend.cmd     # :5173（Vite 代理 /api /openapi /actuator → 8080）

# 只编译后端（必须 clean；打包前先停服务，否则 Windows 无法替换 jar）
build-backend.cmd
# 等价：
cd baseplate; mvn -pl baseplate-bootstrap -am -DskipTests clean package

# 前端
cd baseplate-console; npm install; npm run dev; npm run build
```

## CONVENTIONS
- 缩进：Java 4 空格；TS/Vue/YAML 2 空格。行宽尽量 ≤120。
- 后端每个模块固定四层：`domain` / `application` / `adapter/{in/out}` / `autoconfigure`。
- 类名后缀：`*Controller` `*Service` `*Dao` `*Repository` `*Filter` `*Interceptor` `*Aspect` `*Properties` `*Row/*View/*Command`(record)。禁用 `*Manager/*Helper/*Util`。
- DTO 一律包装类型（`Boolean/Integer`），Jackson 3 对原始类型 null 直接 400。
- 每张业务表必须 `tenant_id`，每个查询都带它；租户来自 `CurrentUser`，不取请求参数。
- 已执行过的 Flyway 脚本一个字符都不能改，只能新建版本。
- 前端所有请求走 `src/api/client.ts`，禁止页面直接 `import axios`。
- 改了接口/模块清单/环境变量/智能体工具，必须同步 `baseplate/docs/*.md`。

## ANTI-PATTERNS (THIS PROJECT)
六条红线（历史出过真实事故，README 有完整说明）：
1. `BASEPLATE_OPEN_TOKEN_SECRET` 必须与登录 JWT 密钥不同（启动期自检，同值直接失败）。
2. `@OpenApi` 只能标在 `/openapi/**` 处理器上。
3. `subject = USER` 的开放接口只认带 `uid` 的令牌，不得降级为匿名。
4. 模块声明的权限点只能停用，不能删除。
5. `baseplate-api` 不允许依赖任何业务模块（ArchUnit 守着）。
6. 智能体内部工具只读；有副作用一律做外部工具，由前端带用户令牌调用 REST。

其他禁止项：
- `new RuntimeException(...)` 当业务异常（会变 500）；用 `BaseplateException` / `IllegalArgumentException` / `ForbiddenException`。
- Controller 写业务或直接操作 DAO；Service 注入 `HttpServletRequest`。
- SQL `SELECT *` 查敏感表；JSONB 数组用 `?|`（应用 `@>`）。
- 前端手改 `baseplate-bootstrap/src/main/resources/static/`（构建产物）。
- 在 system prompt 里拼可用工具列表（破坏前缀缓存）。
- UI 卡片子类型用 `type`（会覆盖 SSE 事件名，必须用 `kind`）。

## NOTES
- `baseplate-application` 目前只有 `package-info`，是预留扩展位。
- 前端构建产物由 Vite 输出到 `baseplate-console/dist`，再进 bootstrap 静态资源；不要把 dist 当源码。
- `.cmd` 按 GBK 解析，含 UTF-8 中文会把命令拆坏；中文提示放 `.ps1`（UTF-8 with BOM）。
- 验证脚本（e2e.py / check.mjs / verify-*.py）在仓库外的 `创意/build/`，不在本仓库。
- 服务未启动时先看 `compile.log`、`baseplate-console/npm-build*.log`。
