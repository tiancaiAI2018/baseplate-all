# Baseplate · 可复用业务基盘

> 把每个业务系统都要重复建设的横向能力（身份权限、审计、开放平台、工作流、规则、低代码元数据）沉成平台层，
> 业务模块只写纵向逻辑，并以 Maven 依赖方式接入，不重复造轮子。

Spring Boot 4.1.2 · Java 21 · Spring Security 7 · Flowable 8 · MyBatis-Plus 3.5.18 · PostgreSQL + Redis
模块化单体 · 声明式模块清单 · 端口/适配器可替换

---

## 现状

| 指标 | 数值 |
|---|---|
| Maven 模块 | 16（其中 6 个是能力 starter，1 个是唯一可执行制品） |
| Java 源文件 | 180（main，不含测试） |
| REST 方法映射 | 76 |
| 数据库表 | 85（Flowable `act_*` 56 + `flw_*` 6 + 基盘 23） |
| Flyway 迁移 | 12 条，最新 `20261110160000` |
| 端到端断言 | `e2e.py` 35 / `verify_v2.py` 35 / `verify_openapi.py` 24 |
| 控制台页面 | 15（Vue 3 + Vite + Element Plus + bpmn-js） |

详细证据见 [`../Baseplate基盘_实现与验证报告.html`](../Baseplate基盘_实现与验证报告.html)（11 章，含缺陷清单与未完成项）。

---

## 30 秒跑起来

前置：JDK 21、Maven 3.9+、一个可连的 PostgreSQL 14+、一个 Redis（没有 Redis 也能启动，会自动降级到内存）。

```bash
# 1. 构建（打包前务必确认没有正在运行的本服务，否则 jar 被占用无法替换）
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
  --spring.flyway.locations=classpath:db/migration/baseplate,classpath:db/migration/identity,classpath:db/migration/audit,classpath:db/migration/platform,classpath:db/migration/metadata,classpath:db/migration/rule
```

浏览器打开 `http://localhost:8080`，默认账号 `admin`，初始密码取 `BASEPLATE_ADMIN_PASSWORD`（未设置时为 `Admin@12345`）。
**首次登录会强制改密**，改完旧令牌全部失效。

> 本仓库还带一个 `run-server.sh`，它从同目录的村通 `.env` 读取真实连接信息并把库名换成 `baseplate_dev`，
> 只适合当前开发机。拿到别的环境请用上面的环境变量方式。

---

## 文档地图

| 文档 | 解决什么问题 |
|---|---|
| [docs/INDEX.md](docs/INDEX.md) | **总索引**：按任务找文档、AI 阅读顺序、全目录树、术语表 |
| [docs/01-architecture.md](docs/01-architecture.md) | 分层与依赖规则、16 个模块各自职责、16 个端口 |
| [docs/02-directory-map.md](docs/02-directory-map.md) | 逐目录说明；「改某个东西该去哪个文件」 |
| [docs/03-getting-started.md](docs/03-getting-started.md) | 构建、启动、迁移、账号、启动失败排查 |
| [docs/04-api.md](docs/04-api.md) | 76 个 REST 端点全清单（含所需权限点） |
| [docs/05-business-module.md](docs/05-business-module.md) | **新增业务模块从零到上线**：清单、建表、权限、审计、接口 |
| [docs/06-security.md](docs/06-security.md) | 认证、权限、数据权限、开放平台与主体区分 |
| [docs/07-frontend.md](docs/07-frontend.md) | 控制台目录约定、API 层、样式系统、新增页面 |
| [docs/08-conventions.md](docs/08-conventions.md) | 编码规范、SQL 规范、异常与错误码、**禁止项** |
| [docs/09-testing.md](docs/09-testing.md) | 四套验证脚本怎么跑、新功能要补哪些断言 |
| [docs/10-gotchas.md](docs/10-gotchas.md) | 24 条踩过的坑：现象 → 根因 → 正确做法 |

**第一次接触这个项目，按 `INDEX.md` 的「AI 阅读顺序」读**，不要从 01 顺着读到底。

---

## 五个不能碰的约束

这五条是设计红线，改动会连带破坏安全模型，历史上每一条都出过真实事故：

1. **开放平台令牌密钥必须与登录 JWT 密钥不同。** 同值时第三方 access_token 能通过内部 `JwtAuthenticationFilter`，等于越权进 `/api/**`。启动期有自检，同值直接起不来。
2. **`@OpenApi` 只能标在 `/openapi/**` 的处理器上。** 标到 `/api/**` 会在启动期抛异常——这是故意做成硬失败的。
3. **`subject = USER` 的开放接口只认授权码换来的、带 `uid` 的令牌。** 机器令牌到这里必须 403，不允许「降级成匿名用户」放行。
4. **模块声明的权限点不能删除，只能停用。** 删了下次启动会回来，删了等于自欺欺人；注解声明的开放接口同理。
5. **`baseplate-api` 不允许依赖任何业务模块。** 依赖方向由 `baseplate-contract` 里的 ArchUnit 测试守着，反向依赖会编译失败。

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
├── baseplate-workflow-starter/  Flowable 8 封装
├── baseplate-rule-starter/      结构化规则引擎
├── baseplate-metadata-starter/  低代码元数据（表单/列表/流程）
├── baseplate-bootstrap/         唯一可执行制品，内嵌前端静态资源
├── docs/                      本文档目录
└── run-server.sh              本机启动脚本（读村通 .env）

baseplate-console/             前端控制台（与 baseplate/ 同级）
```

---

## 约定

- 文档与代码同步：**改了接口清单、模块清单字段、环境变量、验证脚本，必须同步改对应 md**，见 `docs/INDEX.md` 末尾的维护表。
- 文档只写「当前真实状态」。没实现的能力标「预留」，不要写成已完成。
- 所有命令都在仓库根目录执行，路径按本仓库实际结构书写。
- 文档是否与代码脱节，可以自动查：

```bash
python ../build/verify-docs.py
# PASS 28 处 md 互相引用全部存在
# PASS 04-api.md 覆盖了代码里全部 76 个端点
# ==== 文档与代码一致 ====
```
