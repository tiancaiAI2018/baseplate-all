# BASEPLATE BOOTSTRAP

## OVERVIEW
唯一可执行制品：启动入口、全部配置、Actuator 端口端点、模块注册表持久化、内嵌前端静态资源。

## STRUCTURE
```
com.example.baseplate/
├── BootstrapApplication.java
├── bootstrap/actuator/PortsEndpoint.java              # /actuator/ports
├── bootstrap/startup/ModuleRegistryPersistenceRunner.java
└── resources/application.yml                          # 全部 baseplate.* 默认值 + 环境变量占位
```

## CONFIG
- 数据库：`BASEPLATE_DB_URL` / `BASEPLATE_DB_USER` / `BASEPLATE_DB_PASSWORD`
- Redis：`BASEPLATE_REDIS_HOST` / `_PORT` / `_PASSWORD`
- 密钥：`BASEPLATE_JWT_SECRET`、`BASEPLATE_OPEN_TOKEN_SECRET`（必须不同）、`BASEPLATE_SECRET_KEY`
- 管理员初始密码：`BASEPLATE_ADMIN_PASSWORD`（默认 `Admin@12345`，首登强制改密）
- `spring.flyway.enabled` 默认 false，只有 start-backend 显式打开并传入全部迁移目录。

## CONVENTIONS
- 只做装配与启动，不放业务逻辑。
- 新增环境变量必须同步 `application.yml`、`docs/03-getting-started.md`、根 `.env` 说明。
- 启动期 `registry-fail-fast=true`：清单不合规直接失败，不要为了启动关掉。

## ANTI-PATTERNS
- 手改 `src/main/resources/static/`（前端构建产物）。
- 打包时服务未停（Windows 无法替换 jar）。
- 在 bootstrap 里加 Controller 或 DAO。
