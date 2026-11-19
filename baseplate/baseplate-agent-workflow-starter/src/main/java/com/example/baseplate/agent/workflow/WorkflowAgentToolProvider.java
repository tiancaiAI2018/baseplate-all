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
 *   <li>查询/读取：服务端内部执行，只读。个人待办、我发起的、办理进度、我的已办都在这一层。</li>
 *   <li>创建草稿 / 读取画布：外部工具，必须由前端执行（设计器画布只有浏览器里有）。
 *       模型不能声称“已创建/已部署”，只能等前端回传结果。</li>
 * </ul>
 *
 * <p>同意、驳回、打回不再提供工具：它们只是把 HTTP 接口包一层，
 * 由模型代签会让审批责任变模糊。办理统一由用户本人点「办理」完成。</p>
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
                        "读取任务的多份表单（formDefinitions，每份含 formCode/字段/已填数据）、"
                                + "节点字段权限和可打回的历史节点。用户问「这单要填什么」「之前填了什么」时使用；"
                                + "要办理请引导用户点列表卡片或待办页里的「办理」按钮，AI 不代签。",
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
                        - 表单是多份的：流程级基础表单由用户在「发起配置」里统一设置，
                          所有节点自动继承、节点端不能删除。config.formCodes 只写
                          「本节点新增」的表单编码数组（可多个），不要重复填基础表单；
                        - 每个 APPROVAL 节点都应配置 decisionConfig.buttons，至少包含
                          APPROVE（同意）和 REJECT（驳回，建议 reasonRequired=true），
                          否则用户办理时看不到完整的审批按钮；
                        - 字段权限是二维的：config.fieldPolicy 的第一层键是表单编码，
                          第二层键是字段名，值是 EDITABLE/READONLY/HIDDEN。
                          典型用法：发起人可填的字段，审批节点对表单 A 设为 READONLY（能看不能改）；
                          敏感字段（如薪资、身份证）对无关审批节点设为 HIDDEN（看不到）。
                          字段名必须来自第一层键对应表单的真实字段，先调用 form_get_draft
                          确认字段清单，绝不要臆造字段名；没列出来的字段默认可编辑。
                          服务端会强制校验，前端置灰/隐藏只是体验；
                        - 驳回目标可以在图上画出来：从审批节点画一条 edge 指回前面的审批节点，
                          并设 rejectRoute: true，这条线就是「驳回路由」。可以在 condition 写条件，
                          例如 ${days > 5} 表示天数大于 5 时退到该线指向的节点。
                          用户点驳回时按条件选目标；没画驳回路由或条件都没命中，
                          才兜底退回上一个已完成任务。
                        - 驳回路由的 condition 只在驳回时求值，正向审批不会沿它绕回去（编译期已加守卫）。
                          没画驳回路由时保持旧语义，不要为了「保险」把每条线都标成 rejectRoute。
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
                        "workflow_list_my_started", "查询我发起的流程",
                        """
                        查询我发起的流程实例，默认只看还没走完的。
                        - state：RUNNING（默认，未走完）/ FINISHED（已结束）/ ALL；
                          用户没特别说「历史」「全部」时不要传 ALL；
                        - 返回 instanceId、业务单号、当前停在哪些任务、打回次数；
                        - 要看某一单具体走到哪一步，用返回的 instanceId 调
                          workflow_instance_progress；不要凭空猜 instanceId；
                        - 前端会把它渲染成实例列表卡片（带「看进度」按钮），
                          正文里不用再逐条复述所有字段，挑用户关心的说。
                        """,
                        ToolType.BUILTIN_INTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_instance_progress", "查询办理进度",
                        """
                        查询某个流程实例的办理进度：当前停在哪些节点、已经走过哪些节点、
                        被谁打回过、每个任务的办理人和结论。
                        - 入参二选一：instanceId（来自 workflow_list_my_started）
                          或 businessKey（用户直接说的业务单号）；
                        - 业务单号匹配到多条时，工具会返回 ambiguous=true 和候选列表，
                          此时把候选列给用户确认，不要自己挑一个；
                        - 返回的 ui 会让前端渲染带高亮的办理进度图，
                          正文里不要再手写 ASCII 流程图或用文字描述连线；
                        - 数据范围由服务端校验，查不到就如实说不在可见范围内，不要换 id 重试。
                        """,
                        ToolType.BUILTIN_INTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_list_my_done", "查询我的已办",
                        """
                        查询我审批过的历史任务，带审批结论（同意/驳回）和意见。
                        用户问「我审批过什么」「我上次怎么批的」时使用。
                        - 返回 instanceId，可继续用 workflow_instance_progress 看整单进度；
                        - 前端会渲染成列表卡片。
                        """,
                        ToolType.BUILTIN_INTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "workflow_my_summary", "我的流程概览",
                        """
                        查询我的流程概览数字：待办数、我发起且未走完、我发起且已结束、我已办。
                        用户问「我还有多少事没处理」「我最近提交了多少单」时使用。
                        - 只返回数字，不返回明细；要看明细再用对应的列表工具；
                        - 前端会渲染成四个数字卡片，正文里不要重复罗列每个数字。
                        """,
                        ToolType.BUILTIN_INTERNAL, true, false,
                        ANY_PAGE, "wf:task", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler));
    }

    private static Map<String, Object> emptyParamsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
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
                "formCodes", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "本节点新增绑定的表单编码数组（可多个）。"
                                + "流程级基础表单由用户统一配置、所有节点自动继承，这里不要重复填。"),
                "fieldPolicy", Map.of("type", "object",
                        "description", "节点字段权限，二维结构：第一层键是表单编码，第二层键是字段名。"
                                + "值只能是 EDITABLE（可编辑）/READONLY（只读，能看不能改）/"
                                + "HIDDEN（隐藏，本节点看不到也不下发），不填的字段默认可编辑。"
                                + "例如 {\"expense_form\":{\"amount\":\"READONLY\"}}。"
                                + "字段名必须来自对应表单的真实定义，不要臆造；只需列出非 EDITABLE 的字段。",
                        "additionalProperties", Map.of("type", "object",
                                "additionalProperties", Map.of("type", "string",
                                        "enum", List.of("EDITABLE", "READONLY", "HIDDEN")))),
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
                        "description", "仅在连线没有 condition 时生效，作为所有条件都不满足时的兜底分支"),
                "rejectRoute", Map.of("type", "boolean",
                        "description", "是否为驳回路由：true 时这条线只在用户点驳回时按 condition 选目标，"
                                + "目标通常画回前面的审批节点；不填或 false 表示普通正向连线。"
                                + "多条驳回路由的条件应尽量互斥；一条都不命中时退回上一个已完成任务。")));
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
