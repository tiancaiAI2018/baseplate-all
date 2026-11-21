# BASEPLATE DOCS

## OVERVIEW
后端权威文档（01~10 + INDEX）。文档只写当前真实状态，与代码同步是硬要求。

## MAP
| File | Covers |
|------|--------|
| `INDEX.md` | 按任务找文档、AI 阅读顺序、目录树、术语表 |
| `01-architecture.md` | 分层、18/19 模块职责、端口、AI 编排层 |
| `02-directory-map.md` | 逐目录职责 |
| `03-getting-started.md` | Windows 启动、构建、迁移、账号、排错 |
| `04-api.md` | 全部 REST 端点 + 权限点 + SSE 事件 |
| `05-business-module.md` | 新增业务模块模板 |
| `06-security.md` | 认证、权限、数据权限、开放平台、工具安全边界 |
| `07-frontend.md` | 控制台目录、API 层、助手、样式、新增页面 |
| `08-conventions.md` | 编码/SQL/异常规范与禁止项 |
| `09-testing.md` | 验证脚本与断言口径 |
| `10-gotchas.md` | 历史踩坑（现象→根因→做法） |
| `docs/`（子目录） | 旧版中文文档与 ADR 归档，非当前权威 |

## CONVENTIONS
- 改接口/路由/权限 → 同步 `04-api.md`。
- 改模块清单字段、环境变量、智能体工具 → 同步对应文档。
- 新增文档必须从 `INDEX.md` 可达；文档互相引用路径必须存在。
- 未实现能力标「预留」，不要写成已完成。

## NOTES
文档一致性检查脚本 `verify-docs.py` 在仓库外 `创意/build/`，不在本仓库。
