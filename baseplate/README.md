# Baseplate · 可复用业务基盘

> 把每个业务系统都要重复建设的横向能力（身份权限、审计、开放平台、工作流、规则、低代码元数据、AI 编排）沉成平台层，
> 业务模块只写纵向逻辑，并以 Maven 依赖方式接入，不重复造轮子。

Spring Boot 4.1.2 · Java 21 · Spring Security 7 · Flowable 8 · MyBatis-Plus 3.5.18 · PostgreSQL + Redis
agentscope-core 2.0.4 · Vue 3 + Vite + Element Plus + bpmn-js
模块化单体 · 声明式模块清单 · 端口/适配器可替换

---

## 现状

| 指标 | 数值 |
|---|---|
| Maven 模块 | 18（其中 9 个能力 starter，1 个唯一可执行制品） |
| Java 源文件 | 247（main，不含测试） |
| REST 方法映射 | 137 |
| 数据库表 | 约 95（Flowable `act_*` 56 + 基盘迁移建表 39） |
| Flyway 迁移 | 21 条，最新 `20261118170000` |
| 智能体内置工具 | 28 个（工作流 10 / 表单 4 / C 端 11 / 平台 2 + 入口 1~3），模型侧常驻默认仅 `baseplate` |
| 控制台 Vue 视图 | 22（含节点属性面板、表单渲染等组件） |

详细证据见 [`../../Baseplate基盘_实现与验证报告.html`](../../Baseplate基盘_实现与验证报告.html)（含缺陷清单与未完成项）。

---

## 30 秒跑起来

### Windows（推荐）

工作区根目录（`baseplate/` 的上一级）已提供前台启动脚本，**所有服务都在各自的控制台窗口里运行**：

```bat
start-all.cmd
```

| 脚本 | 作用 |
|---|---|
| `start-all.cmd` | 打开「后端」「前端」两个窗口 |
| `start-backend.cmd` | 只启后端（8080） |
| `start-frontend.cmd` | 只启前端（5173） |

行为约定：

- 自动读取根目录 `.env`（数据库 / Redis / 密钥 / 模型配置），只注入当前进程环境，不改系统变量
- 自动查找 JDK 21、Maven、Node；后端 jar 不存在时自动打包，前端 `node_modules` 不存在时自动安装
- **Ctrl+C 或关闭窗口即停止服务**，不会留下后台进程
- 只检查环境、不启动服务：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-server.ps1 -Target backend -DryRun`

### Linux / macOS

```bash
# 1. 构建（打包前确认没有正在运行的本服务，否则 jar 被占用）
export JAVA_HOME=/path/to/jdk-21
mvn -DskipTests package

# 2. 配置连接（三选一：环境变量 / application.yml / 直接命令行 -D）
export BASEPLATE_DB_URL="jdbc:postgresql://127.0.0.1:5432/baseplate_dev?sslmode=disable"
export BASEPLATE_DB_USER=postgres
export BASEPLATE_DB_PASSWORD=your-password
export BASEPLATE_JWT_SECRET="$(openssl rand -hex 32)"
export BASEPLATE_OPEN_TOKEN_SECRET="$(openssl rand -hex 32)"   # 必须与上一个不同
export BASEPLATE_SECRET_KEY="$(openssl rand -hex 32)"          # 用于加密库里的应用密钥

# 3. 首次启动把迁移打开（之后可以关掉，由流水线执行）
java -jar baseplate-bootstrap/target/baseplate-bootstrap-1.0.0-SNAPSHOT.jar \
  --spring.flyway.enabled=true \
  --spring.flyway.locations=classpath:db/migration/baseplate,classpath:db/migration/identity,classpath:db/migration/audit,classpath:db/migration/platform,classpath:db/migration/metadata,classpath:db/migration/rule,classpath:db/migration/workflow,classpath:db/migration/agent
```

浏览器打开 `http://localhost:8080`，默认账号 `admin`，初始密码取 `BASEPLATE_ADMIN_PASSWORD`（未设置时为 `Admin@12345`）。
**首次登录会强制改密**，改完旧令牌全部失效。

前端单独开发：

```bash
cd baseplate-console
npm install
npm run dev        # http://127.0.0.1:5173，/api 由 Vite 代理到 8080
```

> 本仓库还带一个旧的 `run-server.sh`（Linux/macOS），仍从 `../村通/03_后端开发/cuntong-server/.env`
> 读 `CT_*` 变量并把库名换成 `baseplate_dev`，只适合原始开发机。
> 当前工作区请优先用根目录 `.env` + `start-*.cmd`；拿到别的环境用环境变量方式。

---

## AI 助手与工具

每个页面通过 `pageKey` 绑定智能体，悬浮助手只在有可用 Agent 的页面出现。工具分两类：

| 类型 | 执行位置 | 例子 | 权限 |
|---|---|---|---|
| 统一入口 | 服务端 | `baseplate`（`find` / `show` / `call`） | 目录按 Agent 绑定、页面、用户权限过滤；执行期再校验 |
| 内部工具 | 服务端 | `workflow_list_my_tasks`、`workflow_list_my_started`、`workflow_instance_progress`、`workflow_list_my_done`、`workflow_my_summary`、`workflow_task_detail`、`form_get_draft`、`platform_whoami` | 工具代码里按用户权限点校验 |
| 外部工具 | 浏览器 | `workflow_get_canvas`、`workflow_create_draft`、`form_get_canvas`、`form_create_draft` | 前端带用户令牌调用业务接口，复用 HTTP 权限与审计 |

关键设计：

- **工具按需加载**：`baseplate.agent.tool-exposure-mode` 三选一，互斥不叠加——`cli`（默认，只常驻 `baseplate`，`find`/`show`/`call`）、`meta`（常驻 `tool_search`/`tool_describe`/`tool_call`）、`direct`（全量直挂，排障用）。数据库里新增的工具自动纳入目录，不需要改代码；收敛模式下 `ASK` 与 `EXTERNAL` 工具也不单独占 token，由入口工具在发现后分别触发人工确认与浏览器执行，只有 `direct` 模式才直挂全部 schema
- **AI 不代签**：同意/驳回/打回不再提供工具，办理由用户在办理抽屉确认；只有必须打开浏览器的创建草稿做成外部工具，`@RequiresPermission`、`@AuditLog` 照常生效
- **页面作用域**：工具声明 `pages`，运行时不匹配当前 `pageKey` 的 schema 不会进模型上下文；会话继续时同步 `session.page_key`，避免恢复流报 `Tool not found`
- **HITL**：审批策略为 `ASK` 的工具先落审批单，用户同意后带 `ConfirmResult` 恢复同一会话；CLI/元工具入口会解析脚本里被调用的工具码，命中 `ASK` 时代表它请求确认，命中 `EXTERNAL` 时抛 `ToolSuspendException` 交给前端执行，两条路径都保持与直连模式一致的人工确认与外部执行语义
- **外部工具结果回传**：前端执行完调用 `/api/agent/external-calls/{callId}/result`，服务端恢复 SSE；多个调用按 `replyId` 收齐后再恢复
- **UI 卡片**：工具结果可带 `ui: { kind: "task_list" | "workflow_preview", ... }`，服务端转成独立 `ui` 事件，前端渲染任务卡片 / bpmn-js 流程图缩略图；`ui` 会从模型上下文剔除
- **技能（Skill）**：`baseplate/skills/<名称>/SKILL.md` 由 `SkillIndexService` 扫描、建索引，可在控制台在线编辑 / zip 导入 / 按 Agent 绑定；收敛模式自带「工具检索」/「工具元调用」两个入口技能，由 `BASEPLATE_AGENT_TOOL_EXPOSURE_SKILL`（默认 `true`）控制是否把技能正文内置进 system prompt 并挂载，`false` 时只保留简短调用规则

默认内置工具：

| 分组 | 工具 |
|---|---|
| 统一入口 | `baseplate`（`find` 检索 / `show` 查看参数 / `call` 执行） |
| 工作流（个人） | `workflow_list_my_tasks`、`workflow_task_detail`、`workflow_list_my_started`、`workflow_instance_progress`、`workflow_list_my_done`、`workflow_my_summary` |
| 工作流（设计） | `workflow_list_definitions`、`workflow_get_draft`、`workflow_create_draft`、`workflow_get_canvas` |
| 表单 | `form_list_definitions`、`form_get_draft`、`form_get_canvas`、`form_create_draft` |
| 平台 | `platform_whoami`、`platform_current_time` |

---

## 文档地图

| 文档 | 解决什么问题 |
|---|---|
| [docs/INDEX.md](docs/INDEX.md) | **总索引**：按任务找文档、AI 阅读顺序、目录树、术语表 |
| [docs/01-architecture.md](docs/01-architecture.md) | 分层与依赖规则、18 个模块职责、AI 编排层、16 个端口 |
| [docs/02-directory-map.md](docs/02-directory-map.md) | 逐目录说明；「改某个东西该去哪个文件」 |
| [docs/03-getting-started.md](docs/03-getting-started.md) | Windows 一键启动、构建、迁移、账号、启动失败排查 |
| [docs/04-api.md](docs/04-api.md) | REST 端点全清单（含权限点）与智能体 SSE 事件 |
| [docs/05-business-module.md](docs/05-business-module.md) | **新增业务模块从零到上线**：清单、建表、权限、审计、接口 |
| [docs/06-security.md](docs/06-security.md) | 认证、权限、数据权限、开放平台、智能体工具的安全边界 |
| [docs/07-frontend.md](docs/07-frontend.md) | 控制台目录约定、API 层、AI 助手、样式系统、新增页面 |
| [docs/08-conventions.md](docs/08-conventions.md) | 编码规范、SQL 规范、异常与错误码、**禁止项** |
| [docs/09-testing.md](docs/09-testing.md) | 验证脚本怎么跑、智能体怎么验、新功能要补哪些断言 |
| [docs/10-gotchas.md](docs/10-gotchas.md) | 踩过的坑：现象 → 根因 → 正确做法（含 AI 与 Windows 脚本） |

**第一次接触这个项目，按 `INDEX.md` 的「AI 阅读顺序」读**，不要从 01 顺着读到底。

---

## 六条不能碰的约束

这六条是设计红线，改动会连带破坏安全模型，历史上每一条都出过真实事故：

1. **开放平台令牌密钥必须与登录 JWT 密钥不同。** 同值时第三方 access_token 能通过内部 `JwtAuthenticationFilter`，等于越权进 `/api/**`。启动期有自检，同值直接起不来。
2. **`@OpenApi` 只能标在 `/openapi/**` 的处理器上。** 标到 `/api/**` 会在启动期抛异常——这是故意做成硬失败的。
3. **`subject = USER` 的开放接口只认授权码换来的、带 `uid` 的令牌。** 机器令牌到这里必须 403，不允许「降级成匿名用户」放行。
4. **模块声明的权限点不能删除，只能停用。** 删了下次启动会回来，删了等于自欺欺人；注解声明的开放接口同理。
5. **`baseplate-api` 不允许依赖任何业务模块。** 依赖方向由 `baseplate-contract` 里的 ArchUnit 测试守着，反向依赖会编译失败。
6. **智能体工具不能绕过 HTTP 层直接改业务数据。** 有副作用的操作一律做成外部工具，由前端带用户令牌调用现有接口；内部工具只做只读查询。否则权限、审计、数据归属校验会被模型绕过。

---

## 顶层目录

```
baseplate/                     后端（本目录，Maven 多模块）
├── baseplate-api/             内核契约：端口、模型、注解、异常。零业务、零 Web 依赖
├── baseplate-domain/          共享领域原语（当前只有 AggregateRoot）
├── baseplate-application/     预留扩展位（当前是 package-info，无实际代码）
├── baseplate-runtime/         运行时：端口注册表、配置解密、租户上下文
├── baseplate-registry/        模块清单解析、校验、迁移计划生成
├── baseplate-starter/         聚合 starter（给业务方引的入口）
├── baseplate-contract/        端口 TCK + ArchUnit 边界测试
├── baseplate-adapters/        MyBatis-Plus / Flyway / Redis 六件套 / 内存降级
├── baseplate-security-starter/  鉴权链、JWT、限流、幂等、一次性链接、权限切面
├── baseplate-identity-starter/  用户、角色、组织、权限目录
├── baseplate-audit-starter/     访问/业务/登录三类审计
├── baseplate-platform-starter/  开放平台（双凭证 + 注解暴露 + 授权码）
├── baseplate-workflow-starter/  Flowable 8 封装、流程草稿、节点配置编译
├── baseplate-rule-starter/      结构化规则引擎
├── baseplate-metadata-starter/  低代码元数据（表单/列表/流程）
├── baseplate-agent-starter/     智能体：定义、会话、工具、技能、HITL、SSE
├── baseplate-agent-workflow-starter/  智能体的工作流/表单工具声明
├── baseplate-bootstrap/         唯一可执行制品，内嵌前端静态资源
├── docs/                      本文档目录
├── skills/                    智能体技能目录（SKILL.md）
└── run-server.sh              本机启动脚本（读 .env）

baseplate-console/             前端控制台（与 baseplate/ 同级）
scripts/dev-server.ps1         前后端公共启动逻辑
start-all.cmd / start-backend.cmd / start-frontend.cmd    Windows 前台启动脚本
.env                           本机连接信息（已 gitignore）
```

---

## 约定

- 文档与代码同步：**改了接口清单、模块清单字段、环境变量、智能体工具、验证脚本，必须同步改对应 md**，见 `docs/INDEX.md` 末尾的维护表。
- 文档只写「当前真实状态」。没实现的能力标「预留」，不要写成已完成。
- 所有命令都在仓库根目录执行，路径按本仓库实际结构书写。
- 文档是否与代码脱节，可以自动查：

```bash
python ../../build/verify-docs.py
# PASS 28 处 md 互相引用全部存在
# PASS 04-api.md 覆盖了代码里全部端点
# ==== 文档与代码一致 ====
```
