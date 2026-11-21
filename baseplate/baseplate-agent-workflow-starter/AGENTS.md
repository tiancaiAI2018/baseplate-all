# AGENT WORKFLOW STARTER

## OVERVIEW
把工作流/表单能力声明为智能体工具：内置只读查询 + 外部浏览器执行工具。装配进 `baseplate-agent-starter` 的 `ToolRegistry`。

## STRUCTURE
```
com.example.baseplate.agent.workflow/
├── WorkflowAgentToolProvider.java  # 注册工作流工具
├── WorkflowAgentTools.java         # 工作流工具实现
├── FormAgentToolProvider.java      # 注册表单工具
├── FormAgentTools.java             # 表单工具实现
└── autoconfigure/AgentWorkflowAutoConfiguration.java
```

## TOOL RULES
- 内部工具只读：`workflow_list_my_tasks` / `workflow_task_detail` / `workflow_instance_progress` / `form_get_draft`。
- 有副作用一律 external：`workflow_create_draft` / `workflow_get_canvas` / `form_create_draft` / `form_get_canvas`，由前端带用户令牌调 REST。
- 每个工具必须声明 `pages` 与 `permissionCode`；`code` 全局唯一。
- 工具描述给模型看，写清何时调用；不要在 system prompt 里重复工具清单。

## ANTI-PATTERNS
- 在工具里直接写业务表（绕过权限/审计/数据归属）。
- 用 `username` 和 `userId` 混用办理人标识。
- 新增工具不更新 `ToolDescriptor` 元数据或模块清单。

## TESTS
`src/test/java/.../WorkflowAgentToolsTest.java`：工具声明与行为。
