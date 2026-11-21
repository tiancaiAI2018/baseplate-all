# 03 · 构建、启动与排错

## 本文档解决什么

从零把这套东西跑起来，以及**起不来的时候按什么顺序查**。

---

## 一、环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 项目用了 virtual threads、`record`、`switch` 模式匹配 |
| Maven | 3.9+ | |
| PostgreSQL | 14+ | 需要 `jsonb` 与 `gen_random_uuid()` |
| Redis | 6+ | 可选。没有也能启动，六个端口自动降级到内存（多实例下限流/幂等不再准确） |
| Node.js | 18+ | 只在改前端时需要 |

---

## 二、配置

### 环境变量（推荐）

| 变量 | 默认值 | 必填 | 说明 |
|---|---|---|---|
| `BASEPLATE_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/baseplate_dev?sslmode=disable` | ✅ | 远程库常需 `sslmode=disable` |
| `BASEPLATE_DB_USER` | `postgres` | ✅ | |
| `BASEPLATE_DB_PASSWORD` | 空 | ✅ | |
| `BASEPLATE_REDIS_HOST` | `127.0.0.1` | | 连不上会降级 |
| `BASEPLATE_REDIS_PORT` | `6379` | | |
| `BASEPLATE_REDIS_PASSWORD` | 空 | | |
| `BASEPLATE_JWT_SECRET` | `local-dev-jwt-secret-change-me` | 生产必改 | 内部登录 JWT 签名密钥 |
| `BASEPLATE_OPEN_TOKEN_SECRET` | `local-dev-open-token-secret` | 生产必改 | **开放平台令牌密钥，必须与上一个不同** |
| `BASEPLATE_SECRET_KEY` | `local-dev-only-change-me-secret-key` | 生产必改 | AES-GCM 密钥，用于加密库里的应用 client_secret |
| `BASEPLATE_ADMIN_PASSWORD` | `Admin@12345` | | 首次启动创建 admin 时用的初始密码 |
| `BASEPLATE_AGENT_MODEL` | 空 | | 首次启动写入智能体种子配置的模型名，如 `MiniMax-M3.1-Flash` |
| `BASEPLATE_AGENT_BASE_URL` | 空 | | OpenAI 兼容端点，如 `https://api.minimax.cn/v1` |
| `BASEPLATE_AGENT_API_KEY` | 空 | | 模型调用密钥；只在首次种子写入时使用，之后以控制台保存的配置为准 |
| `BASEPLATE_AGENT_TOOL_EXPOSURE_MODE` | `cli` | | 内部工具暴露方式：`cli`（`baseplate` 命令行）/ `meta`（三个元工具）/ `direct`（全量直挂） |
| `BASEPLATE_AGENT_TOOL_EXPOSURE_SKILL` | `true` | | 收敛模式是否自动挂载入口技能（`cli`→工具检索 / `meta`→工具元调用）；技能正文内置进 system prompt，关闭后只保留简短调用规则 |

> 后三个密钥生产环境用 `openssl rand -hex 32` 生成，且**不能共用**。
> `JWT_SECRET` 与 `OPEN_TOKEN_SECRET` 相同时，`openTokenSecretGuard` 会在启动期抛异常。
> `SECRET_KEY` 换掉后，库里已加密的 client_secret 全部解不开，等于所有应用要轮换密钥。
> 智能体模型密钥不会回显到前端；控制台只返回模型名与端点，密钥保存后不再展示。

### 配置文件

`baseplate-bootstrap/src/main/resources/application.yml`：

| 配置 | 默认 | 说明 |
|---|---|---|
| `spring.flyway.enabled` | `false` | **默认关闭**。本地首次启动显式打开，生产由流水线执行 |
| `baseplate.registry-fail-fast` | `true` | 模块清单不合规直接启动失败 |
| `baseplate.migration-plan-path` | `build/migration-plan.json` | 迁移计划输出位置（只生成不执行） |
| `baseplate.tenant.mode` | `shared-column` | 共享表 + 租户判别列 |
| `baseplate.redis.enabled` | `true` | |
| `baseplate.redis.fallback-to-memory` | `true` | 连不上 Redis 时降级 |
| `baseplate.redis.key-prefix` | `bp:` | |
| `baseplate.security.trace-header` | `X-Trace-Id` | |
| `baseplate.identity.default-tenant` | `default` | |
| `baseplate.identity.password-expire-enabled` | `false` | 按 NIST 默认不强制过期 |
| `baseplate.identity.password-expire-days` | `90` | 开启过期后生效 |
| `baseplate.identity.max-failed-attempts` | `5` | 连续失败锁定阈值 |
| `baseplate.identity.lock-minutes` | `15` | 锁定时长 |
| `baseplate.platform.token-ttl` | `2h` | 开放 access_token 有效期 |
| `baseplate.platform.auth-code-ttl` | `5m` | 授权码有效期 |
| `baseplate.platform.refresh-ttl` | `30d` | 刷新令牌有效期 |
| `baseplate.platform.timestamp-window` | `5m` | 签名时间戳窗口 |
| `baseplate.platform.sandbox-enabled` | `true` | 是否允许沙盒调用 |

---

## 三、构建

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

# 全量构建（跳过测试）
mvn -DskipTests package

# 只想编译（更快，改完先看有没有语法错）
mvn -DskipTests compile
```

产物：`baseplate-bootstrap/target/baseplate-bootstrap-1.0.0-SNAPSHOT.jar`（约 66 MB，内置前端）。

### 构建相关的三个坑

| 现象 | 原因 | 解决 |
|---|---|---|
| 打包报 `Failed to replace ... jar` | 服务还开着，jar 被占用 | **先停服再打包** |
| Maven 报 `AccessDeniedException` 写 `.lastUpdated` | `~/.m2` 是符号链接/权限受限 | 加 `-Dmaven.repo.local=D:/dev-tools/apache-maven-3.9.14/repository`（换成本机真实仓库路径） |
| `mvn` 命令找不到 | `D:/...` 直接拼进 `PATH` 会被冒号当分隔符 | 在 Git Bash 里写 `/d/dev-tools/apache-maven-3.9.14/bin` |

---

## 四、启动

### Windows 一键启动（推荐）

仓库根目录（`baseplate-all/`）已提供前台启动脚本：

```bat
start-all.cmd        :: 前后端各开一个窗口
start-backend.cmd    :: 只启后端（8080）
start-frontend.cmd   :: 只启前端（5173）
```

这三个脚本最终都调用 `scripts/dev-server.ps1`，行为固定：

| 行为 | 说明 |
|---|---|
| 前台运行 | Ctrl+C 或关闭窗口即退出，不会留下后台进程 |
| 读 `.env` | 只注入当前进程环境，不改系统变量 |
| 自动找工具 | `JAVA_HOME` → PATH → `%USERPROFILE%\.jdks\*`；Maven 同理 |
| 自动补齐 | 没有 jar 时打包；没有 `node_modules` 时 `npm install` |
| 自检 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-server.ps1 -Target backend -DryRun` |

> `.cmd` 文件必须是**纯 ASCII**：cmd 按系统 GBK 代码页解析批处理，文件里有 UTF-8 中文会把 `start` 命令拆坏。
> 中文提示统一由 PowerShell 脚本输出（`.ps1` 保存为 UTF-8 BOM）。

### 首次启动（要跑迁移）

```bash
LOCATIONS="classpath:db/migration/baseplate,\
classpath:db/migration/identity,\
classpath:db/migration/audit,\
classpath:db/migration/platform,\
classpath:db/migration/metadata,\
classpath:db/migration/rule,\
classpath:db/migration/workflow,\
classpath:db/migration/agent"

java -jar baseplate-bootstrap/target/baseplate-bootstrap-1.0.0-SNAPSHOT.jar \
  --spring.flyway.enabled=true \
  --spring.flyway.locations="$LOCATIONS"
```

启动日志里应该看到：

```
Successfully validated 21 migrations
当前版本 ... 20261118170000
权限目录已同步：7 个模块，40 个权限点
开放接口目录已同步：4 条（注解 4 条，手工 0 条）
技能目录扫描完成: 3 个
智能体工具注册完成: 28 个
Started BootstrapApplication in 13.5 seconds
```

### 日常启动（不跑迁移）

```bash
java -jar baseplate-bootstrap/target/baseplate-bootstrap-1.0.0-SNAPSHOT.jar
```

### 本机脚本（仅限当前开发机）

```bash
bash run-server.sh --server.port=18081            # 日常启动
bash run-server.sh --server.port=18081 --migrate  # 带 Flyway 全量迁移
```

`--migrate` 等价于上面那串 `--spring.flyway.*` 参数。
它是旧的 Linux/macOS 脚本，仍从 `../村通/03_后端开发/cuntong-server/.env` 读 `CT_*` 变量；
当前工作区请优先用根目录 `.env` + `start-*.cmd`。

> 漏掉 `workflow` 迁移目录时，`wf_operation_log` 不建表；漏掉 `agent` 迁移目录时 `agent_*` 表不存在，助手一打开就 500。
换成别的机器这个脚本就跑不了，用环境变量方式。

### 健康检查

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP"}
curl http://localhost:8080/actuator/ports           # 16 个端口各自绑到了哪个实现
```

`/actuator/ports` 是这套东西最有用的自检端点：能看到 `RateLimiter → RedisRateLimiter` 还是 `MemoryRateLimiter`。

---

## 五、账号

| 项 | 值 |
|---|---|
| 租户 | `default` |
| 用户名 | `admin` |
| 初始密码 | `$BASEPLATE_ADMIN_PASSWORD`，未设置时为 `Admin@12345` |
| 角色 | `SUPER_ADMIN`（权限校验短路，所有接口都能调） |

**首次登录会强制改密**：后端返回 `403 PASSWORD_CHANGE_REQUIRED`，前端自动跳 `/change-password`。
改密后该用户所有旧令牌立即失效（`TokenBlacklist.revokeAllForUser`）。

密码策略（`PasswordPolicy`）：长度 ≥ `baseplate.identity.password-min-length`（默认 8）、
必须同时含大写、小写、数字、**不能包含用户名**、不能与最近 `password-history-size`（默认 5）次重复。
所以测试时别用 `Admin@2026!`——它含 `admin`，会被策略拒绝。

---

## 六、启动失败排查

按这个顺序查，能覆盖绝大多数情况。

### 1. 端口被占用

```bash
netstat -ano | grep ":8080"
taskkill //PID <pid> //F      # Git Bash 下双斜杠
```

### 2. 模块清单不合规

日志特征：`IllegalStateException` + 提到 `baseplate-module.yml`。

常见原因：YAML 缩进错、`id` 重复、`depends-on` 指向不存在的模块、`flyway-locations` 路径不存在。
`baseplate.registry-fail-fast=true` 时会直接起不来——这是故意的，清单错了宁可别启动。

### 3. 数据源相关 Bean 没装配

日志特征：某个 starter 的接口全部 404，但启动没有报错。

根因：条件装配写成了 `@ConditionalOnBean(JdbcTemplate.class)`。它比 `JdbcTemplate` 自动配置**更早**求值，
永远不成立。正确写法是 `@ConditionalOnBean(DataSource.class)` + `@ConditionalOnClass(JdbcTemplate.class)`。

### 4. 表不存在

```
ERROR: relation "sys_user" does not exist
```

迁移没跑。用第四节「首次启动」的命令，或检查 `spring.flyway.locations` 是否包含该模块。

### 5. 密钥冲突

```
IllegalStateException: 开放平台令牌密钥不能与登录 JWT 密钥相同
```

给 `BASEPLATE_OPEN_TOKEN_SECRET` 设一个不同的值。

### 6. Redis 连不上

不阻塞启动，但日志会有降级提示。检查 `/actuator/ports` 确认是不是内存实现。
**多实例部署时内存限流是不准的**，生产必须真 Redis。

### 7. 接口 404 但类上有 `@RequestMapping`

Spring Framework 7 移除了「只有 `@RequestMapping` 就算控制器」的行为。类上必须同时有 `@RestController`
（或 `@Controller`）。这类问题编译期不会报，只在运行期 404。

### 8. Flyway 完全不执行且没有报错

Boot 4 把 Flyway 拆到了独立模块，只引 `flyway-core` 不会激活自动配置，必须引 `spring-boot-flyway`。

### 9. Windows 双击启动脚本，报 `'xxx' 不是内部或外部命令` / `'rt' 不是…`

cmd 按系统 GBK 代码页逐行解析批处理文件。`.cmd` 里如果有 UTF-8 中文（尤其是 `start "标题"`），
多字节字符会把命令行拆坏，表现为一个乱码命令加一个 `'rt'`（`start` 的残片）。

解决：`.cmd` 只放 ASCII；中文提示放到 `.ps1`，并保存为 **UTF-8 with BOM**。

### 10. PowerShell 脚本中文乱码，或报「字符串缺少终止符」

Windows PowerShell 5 读取**无 BOM** 的 `.ps1` 时按 ANSI（GBK）解码，UTF-8 中文会被拆成非法字符，
甚至把注释里的字节拼成引号，导致语法错误。用编辑器另存为「UTF-8 with BOM」即可。

---

## 七、确认跑通

```bash
# 登录
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的密码","tenant":"default"}' | jq -r .accessToken)

# 首次登录会返回 mustChangePassword=true，先改密
curl -s -X POST http://localhost:8080/api/auth/password \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"旧","newPassword":"Bp@2026!Secure"}'

# 查目录
curl -s http://localhost:8080/api/identity/permissions -H "Authorization: Bearer $TOKEN" | jq '.groups[].label'
curl -s http://localhost:8080/api/platform/apis -H "Authorization: Bearer $TOKEN" | jq '.items[].code'
```

完整回归用 [09-testing.md](09-testing.md) 里的四套脚本。
