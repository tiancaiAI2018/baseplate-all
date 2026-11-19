package com.example.baseplate.agent.workflow;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.agent.tool.AgentToolSpec;
import com.example.baseplate.agent.tool.ToolDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流工具声明。
 *
 * <ul>
 *   <li>查询/读取：服务端内部执行，只读。</li>
 *   <li>创建草稿：外部工具，参数下发给前端；前端落草稿并打开设计器，
 *       模型不能声称“已创建/已部署”，只能等前端回传结果。</li>
 * </ul>
 */
public final class WorkflowAgentToolProvider implements AgentToolProvider {

    /** 待办查询/办理在所有页面都可用：用户在任何页面都可能问「我有哪些待办」。 */
    private static final Set<String> ANY_PAGE = Set.of();
    private static final Set<String> DESIGNER_PAGES = Set.of("workflow/designer");

    private final WorkflowAgentTools handler;

    public WorkflowAgentToolProvider(WorkflowAgentTools handler) {
        this.handler = handler;
    }

    @Override
    public List<AgentToolSpec> tools() {
        return List.of(
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_list_definitions", "查询流程定义",
                        "列出已部署的工作流定义。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        DESIGNER_PAGES, "wf:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_get_draft", "读取流程草稿",
                        "读取草稿的节点、连线和节点配置，供再次修改时使用。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        DESIGNER_PAGES, "wf:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_list_my_tasks", "查询我的待办",
                        "查询当前用户的待办任务，可返回多条；前端会渲染成任务卡片。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_task_detail", "读取待办详情",
                        "读取任务表单、已填数据和可打回的历史节点。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                // 外部工具：handler 为 null，执行在前端
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_create_draft", "创建流程草稿",
                        """
                        根据节点和连线创建流程草稿，前端会打开设计器供用户查看和修改。
                        - 简单直线流程可以省略 edges，按 nodes 顺序串联；
                        - 有分支时使用 EXCLUSIVE_GATEWAY 节点，并在 edges.condition 写条件，
                          支持 && || ! 和括号，例如 ${amount >= 5000 && dept == 'SALES'}；
                          字符串值用单引号，数字不加引号，条件不满足时流程会报错；
                        - 只有当一条出线不写 condition 时才用 default: true 做兜底分支；
                          有条件的出线不要标 default；
                        - edges 必须完整覆盖所有节点：除 START 外每个节点都要有入线，
                          除 END 外每个节点都要有出线；生成后逐条核对 nodes 列表，
                          漏一条就会报「从 START 不可达」；
                        - 节点 config 可绑定找人方式、表单、签署方式、按钮和超时，
                          这些会同步到右侧节点属性面板；
                        - 每个 APPROVAL 节点都应配置 decisionConfig.buttons，至少包含
                          APPROVE（同意）和 REJECT（驳回，建议 reasonRequired=true），
                          否则用户办理时看不到完整的审批按钮；
                        - 驳回是引擎行为：用户点驳回会自动退回上一个已完成的审批节点，
                          不要在 edges 里画审批节点回到前面节点的回退线——回退线会形成循环，
                          真正生效并出现在流程图上的是「正向」连线。
                        调用成功只代表草稿已打开，不要声称已部署；部署必须由用户在设计器确认。
                        """,
                        ToolType.EXTERNAL, true, false,
                        DESIGNER_PAGES, "wf:deploy", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        draftParamsSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_get_canvas", "读取当前画布",
                        """
                        读取用户当前正在编辑的流程画布（包含未保存的修改），返回
                        processKey、名称、BPMN XML 和结构化 spec（nodes/edges）。
                        - 用户说「看看我现在画的流程」「在当前图上改」时先调用；
                        - spec 与草稿结构同构，修改后交给 workflow_create_draft 即可生成新草稿；
                        - 前端会同时展示一张流程图卡片；
                        - 读取失败会返回原因（例如画布还没初始化），如实告诉用户即可。
                        """,
                        ToolType.EXTERNAL, true, false,
                        DESIGNER_PAGES, "wf:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        emptyParamsSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_complete_task", "办理待办（同意/驳回）",
                        """
                        帮用户办理待办任务：同意（APPROVE）或驳回（REJECT）。
                        - 必须先调用 workflow_list_my_tasks 找到 taskId，
                          再用 workflow_task_detail 确认任务与按钮；
                        - APPROVE 表示同意并流转到下一节点；REJECT 表示驳回，
                          引擎自动退回上一个已完成的审批节点（不要自己猜目标节点）；
                        - comment 是办理意见，REJECT 时必填并作为退回原因；
                        - 用户要求「打回到某个指定节点」时不要用本工具，
                          改用 workflow_rollback_task；
                        - 前端会直接提交，调用后等待工具结果，不要提前声称成功。
                        """,
                        ToolType.EXTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        completeTaskParamsSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_rollback_task", "把任务打回到指定节点",
                        """
                        把待办任务打回到指定的历史节点（非 BPMN 标准能力）。
                        - 先调用 workflow_task_detail 获取 rollbackTargets，只能使用列表里的 nodeId；
                        - 用户说的节点名称要能在 rollbackTargets 中找到对应项，
                          找不到就说明可打回节点只有哪些，不要猜；
                        - reason 必填，会记录到流程轨迹；
                        - 如果用户只是想退回上一个节点，用 workflow_complete_task(REJECT) 更合适；
                        - 前端会直接提交，等待工具结果后再回复。
                        """,
                        ToolType.EXTERNAL, true, false,
                        ANY_PAGE, "wf:rollback", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        rollbackTaskParamsSchema()), null));
    }

    private static Map<String, Object> emptyParamsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    /** 办理参数：decision 只允许两个值，避免模型发明「同意但退回」之类的组合。 */
    private static Map<String, Object> completeTaskParamsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "taskId", Map.of("type", "string",
                        "description", "待办任务 id，来自 workflow_list_my_tasks"),
                "decision", Map.of("type", "string", "enum", List.of("APPROVE", "REJECT"),
                        "description", "APPROVE=同意，REJECT=驳回（退回上一个已完成审批节点）"),
                "comment", Map.of("type", "string",
                        "description", "办理意见；驳回时必填，作为退回原因"),
                "values", Map.of("type", "object",
                        "description", "可选：表单字段值，键为字段名")));
        schema.put("required", List.of("taskId", "decision"));
        return schema;
    }

    private static Map<String, Object> rollbackTaskParamsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "taskId", Map.of("type", "string", "description", "待办任务 id"),
                "targetNodeId", Map.of("type", "string",
                        "description", "目标节点 id，必须来自 workflow_task_detail 的 rollbackTargets.nodeId"),
                "reason", Map.of("type", "string", "description", "打回原因，必填")));
        schema.put("required", List.of("taskId", "targetNodeId", "reason"));
        return schema;
    }

    /** 模型看到的参数结构；前端与后端按同一结构消费。 */
    private static Map<String, Object> draftParamsSchema() {
        Map<String, Object> nodeConfig = new LinkedHashMap<>();
        nodeConfig.put("type", "object");
        nodeConfig.put("description", "节点在属性面板里的配置，可只填需要的项");
        nodeConfig.put("properties", Map.of(
                "assigneeType", Map.of("type", "string",
                        "enum", List.of("INITIATOR", "USER", "ROLE", "DEPT_MANAGER",
                                "ORG_ROLE", "FORM_FIELD")),
                "assigneeValue", Map.of("type", "string",
                        "description", "用户 ID / 角色编码 / 组织ID:角色 / 表单字段名"),
                "signType", Map.of("type", "string",
                        "enum", List.of("SINGLE", "COUNTERSIGN", "SEQUENTIAL", "OR_SIGN")),
                "signRatio", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                "formCode", Map.of("type", "string", "description", "绑定的表单编码"),
                "timeoutMinutes", Map.of("type", "integer"),
                "decisionConfig", Map.of("type", "object",
                        "description", "审批按钮配置，例如 {\"buttons\":["
                                + "{\"code\":\"APPROVE\",\"label\":\"同意\",\"reasonRequired\":false,\"reasonMinLength\":0},"
                                + "{\"code\":\"REJECT\",\"label\":\"驳回\",\"reasonRequired\":true,\"reasonMinLength\":2}]}"),
                "triggerConfig", Map.of("type", "object",
                        "description", "{\"onEnter\":\"触发动作编码\"}")));

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        node.put("properties", Map.of(
                "id", Map.of("type", "string", "description", "节点唯一 id"),
                "name", Map.of("type", "string", "description", "节点名称"),
                "type", Map.of("type", "string",
                        "enum", List.of("START", "APPROVAL", "EXCLUSIVE_GATEWAY", "END")),
                "assignee", Map.of("type", "string",
                        "description", "兼容字段；表达式如 ${supervisor}。复杂找人逻辑请用 config"),
                "config", nodeConfig));
        node.put("required", List.of("id", "name", "type"));

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("type", "object");
        edge.put("properties", Map.of(
                "from", Map.of("type", "string"),
                "to", Map.of("type", "string"),
                "name", Map.of("type", "string"),
                "condition", Map.of("type", "string",
                        "description", "网关出线条件表达式。支持 && || ! 和括号，"
                                + "例如 ${amount >= 5000 && (dept == 'SALES' || urgent == true)}；"
                                + "字符串值用单引号，数字不加引号；不确定优先级时加括号"),
                "default", Map.of("type", "boolean",
                        "description", "仅在连线没有 condition 时生效，作为所有条件都不满足时的兜底分支")));
        edge.put("required", List.of("from", "to"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "processKey", Map.of("type", "string",
                        "description", "流程 key，小写字母/数字/中划线"),
                "name", Map.of("type", "string", "description", "流程名称"),
                "description", Map.of("type", "string", "description", "可选的流程说明"),
                "nodes", Map.of("type", "array", "items", node),
                "edges", Map.of("type", "array", "items", edge,
                        "description", "连线；省略时按 nodes 顺序串联")));
        schema.put("required", List.of("processKey", "name", "nodes"));
        return schema;
    }
}
