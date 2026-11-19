package com.example.baseplate.agent.workflow;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.PageQuery;
import com.example.baseplate.api.model.TaskView;
import com.example.baseplate.api.model.WorkflowInstanceDetail;
import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.model.WorkflowTaskDetail;
import com.example.baseplate.api.model.WorkflowTaskTrace;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.workflow.application.WorkflowDraftService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * 工作流工具的服务端部分：只读查询与待办查询。
 *
 * <p>会改数据的操作都在外部工具里：创建草稿、同意/驳回、打回。
 * 它们由前端带着用户令牌调用业务接口，权限、审计与数据归属校验
 * 全部复用 HTTP 层，不会因为「模型能调」而绕过。</p>
 */
public final class WorkflowAgentTools {

    private static final int MAX_TASKS = 20;

    private final WorkflowDraftService draftService;
    private final ProcessEnginePort engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowAgentTools(WorkflowDraftService draftService, ProcessEnginePort engine) {
        this.draftService = draftService;
        this.engine = engine;
    }

    @Tool(name = "workflow_list_definitions",
            description = "列出当前租户已部署的工作流定义（key、名称、版本、是否作废）。用户问「有哪些流程」时使用。")
    public String listDefinitions(RuntimeContext ctx) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:view");
        List<Map<String, Object>> items = engine.definitions().stream().limit(20)
                .map(item -> Map.<String, Object>of(
                        "key", item.key(),
                        "name", text(item.name()),
                        "version", item.version(),
                        "suspended", item.suspended()))
                .toList();
        return mapper.writeValueAsString(Map.of(
                "summary", "共 " + items.size() + " 个流程定义",
                "items", items));
    }

    @Tool(name = "workflow_get_draft",
            description = "读取流程草稿的结构化节点。用户要求修改已有草稿时，先调用它拿到原节点，再生成新的完整节点列表。")
    public String getDraft(RuntimeContext ctx,
                           @ToolParam(name = "draftId", description = "草稿 id") long draftId) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:view");
        String tenant = ctx.get("tenantId");
        var draft = draftService.get(tenant, draftId);
        Object spec = mapper.readValue(draft.spec(), Object.class);
        return mapper.writeValueAsString(Map.of(
                "id", draft.id(),
                "processKey", draft.processKey(),
                "name", text(draft.name()),
                "description", text(draft.description()),
                "spec", spec));
    }

    /**
     * 待办列表：返回 taskId 供后续「同意/驳回/打回」使用。
     *
     * <p>附带 ui 指令让前端渲染任务卡片；ui 只是展示，实际数据仍是 items，
     * 模型与前端消费同一份结构。</p>
     */
    @Tool(name = "workflow_list_my_tasks",
            description = "查询当前用户的待办任务，可返回多条。用户问「我有哪些待办」「有什么要我审批的」时先调用；"
                    + "办理前还要调用 workflow_task_detail 确认任务详情和可打回节点。")
    public String listMyTasks(RuntimeContext ctx,
                              @ToolParam(name = "processKey", required = false,
                                      description = "可选，按流程 key 过滤") String processKey,
                              @ToolParam(name = "keyword", required = false,
                                      description = "可选，匹配任务名或业务单号") String keyword,
                              @ToolParam(name = "limit", required = false,
                                      description = "最多返回条数，默认 10，最大 20") Integer limit) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:task");

        int size = limit == null ? 10 : Math.min(Math.max(limit, 1), MAX_TASKS);
        List<Map<String, Object>> items = engine
                .todo(user, new PageQuery(1, size, null), blankToNull(processKey), blankToNull(keyword))
                .stream()
                .map(WorkflowAgentTools::taskItem)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", items.isEmpty()
                ? "当前没有待办任务"
                : "以下是你的待办任务（最多显示 " + size + " 条）");
        result.put("items", items);
        result.put("ui", taskListCard(items));
        return mapper.writeValueAsString(result);
    }

    /**
     * 任务办理详情：表单字段、已填数据和可回退节点。
     * 回退目标只有 activityId，这里补上节点名称，模型才能把「打回到部门经理」落到正确 id。
     */
    @Tool(name = "workflow_task_detail",
            description = "查询待办任务的详情：表单字段、已填数据、节点按钮，以及可打回的历史节点（nodeId + 名称）。"
                    + "用户要同意、驳回或打回到指定节点时，先调用本工具确认 taskId 和目标 nodeId。")
    public String taskDetail(RuntimeContext ctx,
                             @ToolParam(name = "taskId",
                                     description = "任务 id，来自 workflow_list_my_tasks") String taskId) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:task");

        WorkflowTaskDetail detail = engine.taskDetail(taskId);
        // 只有任务归属人（或管理员）能拿到详情；否则任务 id 泄露就能看别人的单据
        if (!detail.canHandle() && !isSuperAdmin(user)) {
            throw new IllegalStateException("该任务不属于当前用户，无法办理");
        }

        Map<String, String> nodeNames = rollbackNodeNames(detail.task().processInstanceId());
        List<Map<String, Object>> rollbackTargets = detail.rollbackTargets().stream()
                .map(id -> Map.<String, Object>of(
                        "nodeId", id,
                        "name", nodeNames.getOrDefault(id, id)))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", taskItem(detail.task()));
        result.put("canHandle", detail.canHandle());
        result.put("ended", detail.ended());
        result.put("formCode", text(detail.formCode()));
        result.put("formFields", formFields(detail.formSchema()));
        result.put("formData", detail.formData() == null ? Map.of() : detail.formData());
        result.put("decisionButtons", decisionButtons(detail.nodeConfig()));
        result.put("rollbackTargets", rollbackTargets);
        result.put("summary", "任务「" + text(detail.task().name()) + "」"
                + (detail.ended() ? "已结束" : "待办理")
                + "，可打回节点 " + rollbackTargets.size() + " 个");
        return mapper.writeValueAsString(result);
    }

    /** 待办卡片数据：字段名与前端 task_list 卡片约定一致。 */
    private static Map<String, Object> taskListCard(List<Map<String, Object>> items) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("kind", "task_list");
        ui.put("title", items.isEmpty() ? "我的待办：暂无任务" : "我的待办（" + items.size() + " 条）");
        ui.put("tasks", items);
        return ui;
    }

    private static Map<String, Object> taskItem(TaskView task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", text(task.taskId()));
        item.put("name", text(task.name()));
        item.put("processKey", text(task.processKey()));
        item.put("processName", text(task.processName()));
        item.put("businessKey", text(task.businessKey()));
        item.put("startUserName", text(task.startUserName()));
        item.put("createdAt", task.createdAt() == null ? "" : task.createdAt().toString());
        return item;
    }

    /** 历史轨迹里 activityId → 节点名称；拿不到就退回 id，不影响主流程。 */
    private Map<String, String> rollbackNodeNames(String processInstanceId) {
        Map<String, String> names = new HashMap<>();
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return names;
        }
        try {
            WorkflowInstanceDetail instance = engine.instanceDetail(processInstanceId);
            for (WorkflowTaskTrace trace : instance.tasks()) {
                if (trace.activityId() != null && trace.name() != null) {
                    names.putIfAbsent(trace.activityId(), trace.name());
                }
            }
        } catch (RuntimeException ignored) {
            // 已结束或轨迹缺失时只显示节点 id
        }
        return names;
    }

    /** 表单 schema 里的字段清单；完整 schema 对模型是噪音，只给字段定义。 */
    private Object formFields(String formSchema) {
        if (formSchema == null || formSchema.isBlank()) {
            return List.of();
        }
        try {
            Map<?, ?> schema = mapper.readValue(formSchema, Map.class);
            Object fields = schema.get("fields");
            return fields == null ? List.of() : fields;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Object decisionButtons(WorkflowNodeConfig config) {
        if (config == null || config.decisionConfig() == null) {
            return List.of();
        }
        Object buttons = config.decisionConfig().get("buttons");
        return buttons == null ? List.of() : buttons;
    }

    private static boolean isSuperAdmin(CurrentUser user) {
        return user != null && user.roles() != null && user.roles().contains("SUPER_ADMIN");
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 工具执行前按当前用户权限校验；工具本身就是用户能力的延伸。 */
    private static void requirePermission(CurrentUser user, String permission) {
        if (user == null) {
            throw new IllegalStateException("缺少用户上下文");
        }
        if (isSuperAdmin(user)) {
            return;
        }
        if (user.permissions() == null || !user.permissions().contains(permission)) {
            throw new IllegalStateException("当前用户缺少权限：" + permission);
        }
    }
}
