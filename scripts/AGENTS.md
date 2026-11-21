# SCRIPTS (WINDOWS LAUNCH)

## OVERVIEW
PowerShell 启动/构建逻辑。根目录 `.cmd` 只做转发，真正的环境查找与进程管理在这里。

## FILES
| File | Purpose |
|------|---------|
| `dev-server.ps1` | `-Target backend|frontend [-DryRun]`；找 Java/Maven/Node、按需构建或 install、前台运行 |
| `dev-common.ps1` | 公共函数：项目根、`Import-DotEnv`、`Find-Java`、`Find-Maven`、jar 是否过期、`Invoke-BackendBuild` |
| `build-backend.ps1` | 检查 8080 未占用 → `clean package` → 输出 jar 路径/大小 |

## CONVENTIONS
- `.env` 只 `Set-Item Env:` 注入当前进程，不改系统变量。
- 后端源码比 jar 新时自动 `clean package`；首次运行自动构建。
- 前端首次运行自动 `npm install`；直接调 `vite.js`，避免 Ctrl+C 触发 `Terminate batch job?`。
- 用户 `Ctrl+C` / 关闭窗口应视为正常退出（130 / 0xC000013A 归一为 0）。
- `.ps1` 必须保存为 UTF-8 with BOM（含中文提示）；`.cmd` 只放 ASCII，否则 GBK 解析会把命令拆坏。

## ANTI-PATTERNS
- 在 `.cmd` 里写 UTF-8 中文。
- 打包前不检查 8080 是否被占用。
- 用 `npm.cmd` 启动 dev server（Ctrl+C 行为差）。
- 把 `.env` 变量写回用户/系统环境。
