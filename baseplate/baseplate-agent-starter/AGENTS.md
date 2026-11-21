# AGENT STARTER (AI ORCHESTRATION)

## OVERVIEW
智能体能力模块：Agent 定义、会话、工具注册、技能索引、HITL 审批、SSE 流式对话、模型调用观测。基于 agentscope-core。

## STRUCTURE
```
com.example.baseplate.agent/
├── domain/                  AgentDefinition/Session/Message、ApprovalMode/RiskLevel/ToolType
├── application/             Chat/Config/Session/Observe/SkillIndex 服务、TokenMeter、RecordingModel
├── adapter/in/web/          Agent{Chat,Config,ExternalCall,Hitl,Observe,Session,Skill,ToolDirectory}Controller
├── adapter/out/persistence/ Agent*Repository（显式 SQL）
├── autoconfigure/           AgentAutoConfiguration
├── config/                  AgentProperties
├── model/                   MiniMaxOptions 等
├── runtime/                 AgentRuntimeFactory、AgentEventMapper、SseEventWriter、UiAwareToolResultConverter
└── tool/                    ToolRegistry、ToolDescriptor、AgentToolProvider/Spec、HttpApiTool、PlatformTools
resources/db/migration/agent/  # Flyway
resources/META-INF/baseplate-module.yml
```

## TOOL MODEL
| Type | Execution | Examples | Rule |
|------|-----------|----------|------|
| internal | 服务端 | `workflow_list_my_tasks`、`form_get_draft`、`platform_whoami` | 只读；代码内校验 `permissionCode` |
| external | 浏览器 | `workflow_create_draft`、`workflow_get_canvas`、`form_create_draft` | 前端带用户令牌调 REST，复用权限与审计 |

`ToolDescriptor` 字段：`code/name/description/type/builtin/editable/pages/permissionCode/risk/minApproval/defaultApproval/paramsSchema`。
- `pages` 为空或含 `*` 表示全页面；运行时按 `pageKey` 过滤，未匹配的 schema 不进模型上下文。
- 内置工具 code 写死在代码；数据库只同步只读目录，运营只能启停与配置审批策略。
- 工具编码必须全局唯一；新增工具必须声明 `pages` 与 `permissionCode`。

### 工具暴露模式（三选一）
由 `baseplate.agent.tool-exposure-mode`（env `BASEPLATE_AGENT_TOOL_EXPOSURE_MODE`）控制，三种模式互斥、不会同时注册：

| 模式 | 模型看到的工具 | 适用 |
|------|----------------|------|
| `direct` | 每个启用的内部工具各自一条 schema | 工具很少、排障对比 |
| `cli`（默认） | 只有 `baseplate`，脚本内 `find/show/call` | 工具多、追求最少常驻 token |
| `meta` | `tool_search` / `tool_describe` / `tool_call` | 工具多、要求严格 JSON 参数 |

- 三种模式共用 `ToolDirectory`：按 `enabledTools` + 页面 + 权限生成目录，数据库里新增的工具自动可见，无需改代码。
- 收敛模式（`cli`/`meta`）自动挂载入口技能 `工具检索` / `工具元调用`，由 `baseplate.agent.tool-exposure-skill`（env `BASEPLATE_AGENT_TOOL_EXPOSURE_SKILL`，默认 `true`）控制；开启时把 SKILL.md 正文内置进 system prompt（避免模型猜错 `load_skill_through_path` 的 skillId），关闭时只留简短调用规则。技能正文写在 SKILL.md，不要再往 prompt 里拼工具列表。
- `AgentRuntimeFactory` 用影子 `Toolkit` 保存内部工具；入口工具以 `getTool(code).callAsync(param)` 执行，绕过激活门但原样透传 `RuntimeContext`（principal/tenant/pageKey），执行期再校验权限、页面与审批策略。
- 外部工具与 `ASK` 工具始终直接注册在主 toolkit，不走入口工具：前者只能由浏览器执行，后者必须由 PermissionEngine 触发审批。入口工具本身需要显式补 `ALLOW` 权限规则，否则有 ASK 工具时会被默认值误拦。
- 新增 internal 工具只需实现 `AgentToolProvider`，不要手工往主 toolkit 注册。
- 入口工具执行内部工具后必须用 `ToolDirectory.withUi` 透传结果的 `ui` 元数据，否则内部工具（如 `workflow_list_my_tasks`）的 UI 卡片不会生成 `event: ui`。

## CONVENTIONS
- 有副作用的操作只能做 external 工具，不得在内部工具里直接改业务数据。
- 新增内部工具默认走入口工具；只有 external 与 `ASK` 工具需要在 `AgentRuntimeFactory` 里特殊处理。
- system prompt 只放角色、页面边界、失败提示；**不要拼工具列表**（破坏厂商前缀缓存）。
- SSE 事件：`SseEventWriter` 先写 `type=事件名` 再合并 data；UI 卡片子类型必须用 `kind`。
- UI 负载（如 BPMN XML）通过 `resultText` 的 `stripUi` 从模型上下文剔除，只给前端。
- 继续会话时同步 `agent_session.page_key` 为当前页面（`syncPageKey`），否则恢复流报 `Tool not found`。
- HITL：`ASK` 级工具先落审批单，用户同意后以 `ConfirmResult` 恢复同一会话。
- 技能目录 `baseplate/skills/<名称>/SKILL.md`，由 `SkillIndexService` 扫描建索引。
- 新增配置项写进 `baseplate-module.yml` 的 `config-keys`。

## ANTI-PATTERNS
- 内部工具调用 DAO 写入业务表（绕过权限/审计/归属校验）。
- 把 internal 工具重新注册进主 toolkit（常驻 schema 随工具数线性膨胀，抵消 CLI 收益）。
- 工具未声明 `pages` / `permissionCode`，或 code 与已有工具重复。
- 前端流未结束就回传外部工具结果（会与进行中的流抢会话）。
- `ref()` 包装 SSE 或流式状态导致响应丢失。

## TESTS
`baseplate/baseplate-agent-workflow-starter/src/test/java/.../WorkflowAgentToolsTest.java` 覆盖工具声明；改动工具元数据后至少跑该测试。
