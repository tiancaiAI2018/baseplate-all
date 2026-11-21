# BASEPLATE CONSOLE (FRONTEND)

## OVERVIEW
Vue 3 + Vite 6 + TypeScript + Element Plus 控制台，含 tmagic 可视化页面编辑器、bpmn-js 流程设计器、C 端页面 DSL 设计器与智能体助手。Hash 路由，开发端口 5173。

## STRUCTURE
```
src/
├── api/            # 仅此处封装后端调用：client.ts + 各领域 api
├── components/     # 通用组件 + agent/（助手、消息块、BPMN 卡片）+ cend/（DSL 设计器/渲染器）
├── composables/    # useUserDirectory 等组合逻辑
├── layout/         # ConsoleLayout（侧边菜单/顶栏）
├── router/         # 路由表 + 登录/首登改密守卫
├── stores/         # Pinia：auth / workflow / agent
├── types/          # cend.ts 等类型
├── utils/          # bpmnLayout（dagre）/ bpmnSpec
└── views/          # 25 个页面（identity/platform/audit/rule/workflow/cend/agent）
tmagic-runtime/     # 页面运行时独立入口（playground.html / page.html）
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| HTTP 实例/鉴权/错误文案 | `src/api/client.ts` | 唯一 axios 实例；401 跳登录，403 PASSWORD_CHANGE_REQUIRED 跳改密 |
| 路由与权限守卫 | `src/router/index.ts` | `meta.public` 免登录；C 端 `/c/:code` 公开 |
| 登录态/用户 | `src/stores/auth.ts` | Pinia |
| 智能体对话/流/外部工具 | `src/api/agent*.ts`、`src/stores/agent.ts` | SSE、pendingExternal 排队 |
| 智能体 UI | `src/components/agent/` | 消息块、BPMN 卡片、Markdown |
| C 端 DSL | `src/components/cend/cendDsl.ts` | 块类型白名单、样式白名单、动画 |
| 流程画布 | `src/views/WorkflowView.vue`、`utils/bpmnLayout.ts` | bpmn-js + dagre 横向排版 |
| 页面清单与操作 | `src/api/` + `src/views/` | 后端端点见 `../baseplate/docs/04-api.md` |

## COMMANDS
```powershell
npm install
npm run dev      # http://127.0.0.1:5173，Vite 代理 /api /openapi /actuator → BP_API 或 :8080
npm run build    # 输出 dist/，含 main / playground / page 三个入口
npm run preview
```
后端换端口不改编配置：`$env:BP_API='http://localhost:18081'; npm run dev`

## CONVENTIONS
- 所有请求经 `src/api/client.ts`；页面不得直接 `import axios`。
- Pinia setup store；跨页共享状态放 `stores/`。
- 路由使用 `createWebHashHistory`，登录后按 `redirect` 回跳。
- Element Plus 2.9 复选框用 `:model-value` + `@change`，不要用 `:value`。
- bpmn-js 实例必须 `shallowRef`；`ref()` 会把实例包成 Proxy 导致 `elementRegistry` 失效。
- bpmn 元素类型带命名空间（`bpmn:UserTask`），过滤前 `replace(/^bpmn:/,'')` 并转小写。
- C 端块与样式只接受 `cendDsl.ts` 白名单内的值；新增块走 `registerCendBlock`。
- 智能体 UI 卡片子类型字段是 `kind`，不是 `type`（`type` 会覆盖 SSE 事件名）。
- 2 空格缩进；Vue SFC 使用 `<script setup lang="ts">`。

## ANTI-PATTERNS
- 只看 `vite build` 成功就交付：Vite 不检查未定义标识符，运行时才 `ReferenceError`。必须逐页渲染冒烟。
- 直接改 `../baseplate/baseplate-bootstrap/src/main/resources/static/`。
- `ref()` 包裹 bpmn-js 实例或大对象。
- `catch` 静默吞异常（至少 `console.error`，否则界面表现为空数据）。
- 在 `Promise.allSettled` 后统一赋值导致首页被最慢接口拖住；每个数据块独立请求、独立渲染。
- 外部工具结果在 SSE 未结束时直接回传；应进 `pendingExternal`，流结束再 flush。
- 自动化测试用 `dispatchEvent` 点下拉（只改显示层）；应使用真实坐标 `page.mouse.click`。
