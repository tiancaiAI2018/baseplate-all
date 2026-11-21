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

## CONVENTIONS
- 有副作用的操作只能做 external 工具，不得在内部工具里直接改业务数据。
- system prompt 只放角色、页面边界、失败提示；**不要拼工具列表**（破坏厂商前缀缓存）。
- SSE 事件：`SseEventWriter` 先写 `type=事件名` 再合并 data；UI 卡片子类型必须用 `kind`。
- UI 负载（如 BPMN XML）通过 `resultText` 的 `stripUi` 从模型上下文剔除，只给前端。
- 继续会话时同步 `agent_session.page_key` 为当前页面（`syncPageKey`），否则恢复流报 `Tool not found`。
- HITL：`ASK` 级工具先落审批单，用户同意后以 `ConfirmResult` 恢复同一会话。
- 技能目录 `baseplate/skills/<名称>/SKILL.md`，由 `SkillIndexService` 扫描建索引。
- 新增配置项写进 `baseplate-module.yml` 的 `config-keys`。

## ANTI-PATTERNS
- 内部工具调用 DAO 写入业务表（绕过权限/审计/归属校验）。
- 工具未声明 `pages` / `permissionCode`，或 code 与已有工具重复。
- 前端流未结束就回传外部工具结果（会与进行中的流抢会话）。
- `ref()` 包装 SSE 或流式状态导致响应丢失。

## TESTS
`baseplate/baseplate-agent-workflow-starter/src/test/java/.../WorkflowAgentToolsTest.java` 覆盖工具声明；改动工具元数据后至少跑该测试。
