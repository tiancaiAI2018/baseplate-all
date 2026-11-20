package com.example.baseplate.agent.workflow;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.PageQuery;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.api.model.ProcessInstanceView;
import com.example.baseplate.api.model.TaskView;
import com.example.baseplate.api.model.WorkflowDoneTaskView;
import com.example.baseplate.api.model.WorkflowInstanceDetail;
import com.example.baseplate.api.model.WorkflowInstancePage;
import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.model.WorkflowTaskDetail;
import com.example.baseplate.api.model.WorkflowTaskTrace;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.agent.runtime.UiAwareToolResultConverter;
import com.example.baseplate.workflow.application.WorkflowAccessService;
import com.example.baseplate.workflow.application.WorkflowDraftService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.ObjectMapper;

/**
 * 工作流工具的服务端部分：全部是只读查询，服务端直接执行。
 *
 * <p>会改数据的操作不再做成工具。同意/驳回/打回本质上只是把
 * {@code POST /tasks/{id}/complete} 包一层，模型多绕一次没有任何收益，
 * 反而让「谁批的」变模糊；办理统一由本人在办理抽屉里完成。
 * 唯一保留的外部工具是创建草稿——它需要打开前端设计器，服务端做不到。</p>
 */
public final class WorkflowAgentTools {

    private static final int MAX_TASKS = 20;
    private static final int MAX_INSTANCES = 20;
    /** 卡片副标题只显示到分钟，ISO 串太长会把一行撑满。 */
    private static final DateTimeFormatter CARD_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final WorkflowDraftService draftService;
    private final ProcessEnginePort engine;
    private final WorkflowAccessService access;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowAgentTools(WorkflowDraftService draftService, ProcessEnginePort engine,
                              WorkflowAccessService access) {
        this.draftService = draftService;
        this.engine = engine;
        this.access = access;
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
            converter = UiAwareToolResultConverter.class,
            description = "查询当前用户的待办任务，可返回多条。用户问「我有哪些待办」「有什么要我审批的」时先调用；"
                    + "要看某一单填了什么，再用 workflow_task_detail。办理本身不由 AI 执行，"
                    + "引导用户点卡片或待办页的「办理」按钮。")
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
            description = "查询待办任务的详情：多份表单（formDefinitions，每份含 formCode、base 标记、"
                    + "完整字段清单 formFields 和已裁剪数据 formData）、节点按钮，"
                    + "以及可打回的历史节点（nodeId + 名称）。"
                    + "用户问「这单要填什么」「之前填了什么」时调用；"
                    + "AI 不代签，办理由用户在办理抽屉里确认。"
                    + "注意：formData 只含当前节点可见的字段，被设为 HIDDEN 的字段不会出现在 formData 里，"
                    + "这属于正常的字段权限配置，不要当成数据丢失；formFields 仍是完整字段清单。"
                    + "要写表单数据时按 formCode 分桶传 values.formDataByCode，不要混用不同表单的同名字段。")
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
        // 多表单：每份表单独立返回字段清单与已裁剪数据；HIDDEN 字段不在其 formData 里
        List<Map<String, Object>> formDefinitions = new ArrayList<>();
        for (WorkflowTaskDetail.FormView form : detail.formDefinitions()) {
            formDefinitions.add(Map.of(
                    "formCode", text(form.formCode()),
                    "formName", text(form.formName()),
                    "base", form.base(),
                    "formFields", formFields(form.formSchema()),
                    "formData", form.formData() == null ? Map.of() : form.formData()));
        }
        result.put("formDefinitions", formDefinitions);
        result.put("decisionButtons", decisionButtons(detail.nodeConfig()));
        result.put("rollbackTargets", rollbackTargets);
        result.put("summary", "任务「" + text(detail.task().name()) + "」"
                + (detail.ended() ? "已结束" : "待办理")
                + "，可打回节点 " + rollbackTargets.size() + " 个");
        return mapper.writeValueAsString(result);
    }

    /**
     * 我发起的实例。
     *
     * <p>startUserIds 固定成当前用户，不走 data_scope：这个工具回答的就是
     * 「我发起的」，放大到部门范围会让模型把别人的单当成用户的单。</p>
     */
    @Tool(name = "workflow_list_my_started",
            converter = UiAwareToolResultConverter.class,
            description = "查询我发起的流程实例。默认只看还没走完的（state=RUNNING），"
                    + "用户问历史单子时才传 FINISHED 或 ALL。返回 instanceId，"
                    + "要看具体走到哪一步再用 workflow_instance_progress。")
    public String listMyStarted(RuntimeContext ctx,
                                @ToolParam(name = "state", required = false,
                                        description = "RUNNING（默认，未走完）/FINISHED（已结束）/ALL") String state,
                                @ToolParam(name = "processKey", required = false,
                                        description = "可选，按流程 key 过滤") String processKey,
                                @ToolParam(name = "keyword", required = false,
                                        description = "可选，匹配业务单号或流程名") String keyword,
                                @ToolParam(name = "limit", required = false,
                                        description = "最多返回条数，默认 10，最大 20") Integer limit) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:task");
        return listMyStartedFor(user, state, processKey, keyword, limit);
    }

    /** 与 {@link #listMyStarted} 同逻辑，拆出来便于单测直接传 CurrentUser。 */
    String listMyStartedFor(CurrentUser user, String state, String processKey,
                            String keyword, Integer limit) {
        int size = limit == null ? 10 : Math.min(Math.max(limit, 1), MAX_INSTANCES);
        String normalized = normalizeState(state);
        WorkflowInstancePage page = engine.instancePage(1, size, normalized,
                blankToNull(processKey), blankToNull(keyword), Set.of(user.userId()));
        List<Map<String, Object>> items = page.items().stream()
                .map(WorkflowAgentTools::instanceItem)
                .toList();

        String label = "ALL".equals(normalized) ? "我发起的"
                : "FINISHED".equals(normalized) ? "我发起且已结束的" : "我发起且未走完的";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("items", items);
        result.put("summary", items.isEmpty()
                ? label + "流程：没有符合条件的记录"
                : label + "流程共 " + page.total() + " 个，当前返回 " + items.size() + " 个");
        result.put("ui", instanceListCard(label + "（" + page.total() + " 个）", items));
        return mapper.writeValueAsString(result);
    }

    /**
     * 单个实例的办理进度。
     *
     * <p>支持 instanceId 或业务单号两种入口：用户口语里说的是「报销单 BIZ-123」，
     * 强行要求先查 instanceId 会多一轮往返。业务单号命中多条时返回候选，
     * 由模型追问，不猜。</p>
     */
    @Tool(name = "workflow_instance_progress",
            converter = UiAwareToolResultConverter.class,
            description = "查询某个流程实例的办理进度：当前停在哪些节点、已经走过哪些节点、"
                    + "被谁打回过、每个任务的办理人和结论。用户问「我发起的那单走到哪了」"
                    + "「卡在谁那里」时使用。可以传 instanceId，也可以直接传业务单号 businessKey。"
                    + "返回的 ui 会让前端渲染一张带高亮的办理进度图，不要自己用文字画流程图。")
    public String instanceProgress(RuntimeContext ctx,
                                   @ToolParam(name = "instanceId", required = false,
                                           description = "实例 id，来自 workflow_list_my_started") String instanceId,
                                   @ToolParam(name = "businessKey", required = false,
                                           description = "业务单号；没有 instanceId 时用它反查") String businessKey) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:task");
        return instanceProgressFor(user, instanceId, businessKey);
    }

    /** 与 {@link #instanceProgress} 同逻辑，拆出来便于单测。 */
    String instanceProgressFor(CurrentUser user, String instanceId, String businessKey) {
        String id = blankToNull(instanceId);
        if (id == null) {
            String key = blankToNull(businessKey);
            if (key == null) {
                throw new IllegalArgumentException("请提供 instanceId 或业务单号");
            }
            List<ProcessInstanceView> matches = findByBusinessKey(user, key);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("没找到业务单号 " + key + " 对应的流程实例，"
                        + "可能单号写错，或该流程不在你的数据范围内");
            }
            if (matches.size() > 1) {
                List<Map<String, Object>> items = matches.stream()
                        .map(WorkflowAgentTools::instanceItem).toList();
                Map<String, Object> ambiguous = new LinkedHashMap<>();
                ambiguous.put("ambiguous", true);
                ambiguous.put("items", items);
                ambiguous.put("summary", "业务单号 " + key + " 匹配到 " + matches.size()
                        + " 个流程实例，请让用户确认是哪一个后再查进度");
                ambiguous.put("ui", instanceListCard("多个匹配的流程", items));
                return mapper.writeValueAsString(ambiguous);
            }
            id = matches.get(0).id();
        }

        WorkflowInstanceDetail detail = engine.instanceDetail(id);
        // 与 HTTP 接口同一套可见性：管理范围 → 发起人范围 → 参与关系
        access.requireInstanceView(user, id, detail.startUserId());

        Map<String, String> nodeNames = nodeNames(detail);
        List<String> currentNodes = mapNodeNames(detail.currentActivities(), nodeNames);
        List<String> completedNodes = mapNodeNames(detail.completedActivities(), nodeNames);
        List<String> rolledBackNodes = mapNodeNames(detail.rolledBackActivities(), nodeNames);
        String status = instanceStatus(detail.ended(), detail.deleteReason());

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (WorkflowTaskTrace trace : detail.tasks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", text(trace.taskId()));
            item.put("name", text(trace.name()));
            item.put("assignee", text(trace.assignee()));
            item.put("decision", text(trace.decision()));
            item.put("reason", text(trace.reason()));
            item.put("startedAt", trace.startedAt() == null ? "" : trace.startedAt().toString());
            item.put("endedAt", trace.endedAt() == null ? "" : trace.endedAt().toString());
            item.put("ended", trace.ended());
            tasks.add(item);
            if (tasks.size() >= MAX_TASKS) {
                break;
            }
        }

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("kind", "instance_progress");
        ui.put("instanceId", id);
        ui.put("title", instanceTitle(detail));
        ui.put("status", status);
        ui.put("currentNodes", currentNodes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", id);
        result.put("processKey", text(detail.processDefinitionKey()));
        result.put("processName", text(detail.processDefinitionName()));
        result.put("businessKey", text(detail.businessKey()));
        result.put("status", status);
        result.put("ended", detail.ended());
        result.put("currentNodes", currentNodes);
        result.put("completedNodes", completedNodes);
        result.put("rolledBackNodes", rolledBackNodes);
        result.put("rollbackCount", detail.rollbackCount());
        result.put("lastDecision", text(detail.lastDecision()));
        result.put("lastReason", text(detail.lastReason()));
        result.put("startedAt", detail.startedAt() == null ? "" : detail.startedAt().toString());
        result.put("endedAt", detail.endedAt() == null ? "" : detail.endedAt().toString());
        result.put("durationMs", detail.durationMs() == null ? 0L : detail.durationMs());
        result.put("tasks", tasks);
        result.put("ui", ui);
        result.put("summary", "「" + instanceTitle(detail) + "」当前" + status
                + (currentNodes.isEmpty() ? "" : "，停在：" + String.join("、", currentNodes)));
        return mapper.writeValueAsString(result);
    }

    /** 我的已办：我办理过的历史任务，按办结时间倒序。 */
    @Tool(name = "workflow_list_my_done",
            converter = UiAwareToolResultConverter.class,
            description = "查询我的已办任务（我审批过的单子），带审批结论和意见。"
                    + "用户问「我审批过什么」「我上次怎么批的」时使用。")
    public String listMyDone(RuntimeContext ctx,
                             @ToolParam(name = "processKey", required = false,
                                     description = "可选，按流程 key 过滤") String processKey,
                             @ToolParam(name = "keyword", required = false,
                                     description = "可选，匹配任务名或业务单号") String keyword,
                             @ToolParam(name = "limit", required = false,
                                     description = "最多返回条数，默认 10，最大 20") Integer limit) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:task");
        return listMyDoneFor(user, processKey, keyword, limit);
    }

    /** 与 {@link #listMyDone} 同逻辑，拆出来便于单测。 */
    String listMyDoneFor(CurrentUser user, String processKey, String keyword, Integer limit) {
        int size = limit == null ? 10 : Math.min(Math.max(limit, 1), MAX_INSTANCES);
        PageResult<WorkflowDoneTaskView> page = engine.done(user, new PageQuery(1, size, null),
                blankToNull(processKey), blankToNull(keyword));
        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkflowDoneTaskView view : page.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", text(view.taskId()));
            item.put("instanceId", text(view.processInstanceId()));
            item.put("processName", text(view.processName()));
            item.put("businessKey", text(view.businessKey()));
            item.put("name", text(view.name()));
            item.put("decision", text(view.decision()));
            item.put("reason", text(view.reason()));
            item.put("endedAt", view.endedAt() == null ? "" : view.endedAt().toString());
            // 卡片只认 title/subtitle，模型读上面的语义字段，两边共用一份数据
            item.put("title", text(view.name()));
            item.put("subtitle", joinNonBlank(" · ", view.processName(), view.businessKey(),
                    decisionText(view.decision()), timeText(view.endedAt())));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("items", items);
        result.put("summary", items.isEmpty()
                ? "我的已办：没有记录"
                : "我的已办共 " + page.total() + " 条，当前返回 " + items.size() + " 条");
        result.put("ui", instanceListCard("我的已办（" + page.total() + " 条）", items));
        return mapper.writeValueAsString(result);
    }

    /** 个人工作台数字：待办、我发起且运行中、我发起且已结束、我的已办。 */
    @Tool(name = "workflow_my_summary",
            converter = UiAwareToolResultConverter.class,
            description = "查询我的流程概览数字：待办数、我发起且未走完、我发起且已结束、我已办。"
                    + "用户问「我还有多少事没处理」「我最近提交了多少单」时使用。")
    public String mySummary(RuntimeContext ctx) {
        CurrentUser user = ctx.get("principal");
        requirePermission(user, "wf:task");
        return mySummaryFor(user);
    }

    /** 与 {@link #mySummary} 同逻辑，拆出来便于单测。 */
    String mySummaryFor(CurrentUser user) {
        long todo = engine.taskCount(user.userId());
        long running = engine.instancePage(1, 1, "RUNNING", null, null,
                Set.of(user.userId())).total();
        long finished = engine.instancePage(1, 1, "FINISHED", null, null,
                Set.of(user.userId())).total();
        long done = engine.done(user, new PageQuery(1, 1, null), null, null).total();

        List<Map<String, Object>> stats = List.of(
                stat("待办", todo, "条", "等待我处理"),
                stat("我发起", running, "单", "还没走完"),
                stat("已结束", finished, "单", "我发起的已办结"),
                stat("已办", done, "条", "我审批过的"));

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("kind", "stat_group");
        ui.put("title", "我的流程概览");
        ui.put("stats", stats);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("ui", ui);
        result.put("summary", "待办 " + todo + " 条，我发起且未走完 " + running + " 单，"
                + "我发起且已结束 " + finished + " 单，我已办 " + done + " 条");
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

    /** 实例列表卡片：title/subtitle 只负责展示，语义字段仍在 items 里给模型读。 */
    private static Map<String, Object> instanceListCard(String title, List<Map<String, Object>> items) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("kind", "instance_list");
        ui.put("title", title);
        ui.put("items", items);
        return ui;
    }

    private static Map<String, Object> instanceItem(ProcessInstanceView instance) {
        List<String> current = instance.currentTasks() == null ? List.of() : instance.currentTasks();
        String status = instanceStatus(instance.ended(), instance.deleteReason());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instanceId", text(instance.id()));
        item.put("processKey", text(instance.processDefinitionKey()));
        item.put("processName", text(instance.processDefinitionName()));
        item.put("businessKey", text(instance.businessKey()));
        item.put("ended", instance.ended());
        item.put("status", status);
        item.put("currentTasks", current);
        item.put("rollbackCount", instance.rollbackCount());
        item.put("startedAt", instance.startedAt() == null ? "" : instance.startedAt().toString());
        item.put("endedAt", instance.endedAt() == null ? "" : instance.endedAt().toString());
        item.put("title", instance.businessKey() == null || instance.businessKey().isBlank()
                ? text(instance.processDefinitionName()) : instance.businessKey());
        item.put("subtitle", joinNonBlank(" · ", instance.processDefinitionName(),
                current.isEmpty() ? status : "停在：" + String.join("、", current),
                timeText(instance.startedAt())));
        return item;
    }

    /** state 只认三种值；模型可能传 done/ended，统一收敛，避免查询条件拼错。 */
    static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "RUNNING";
        }
        return switch (state.trim().toUpperCase(Locale.ROOT)) {
            case "FINISHED", "DONE", "ENDED", "COMPLETED" -> "FINISHED";
            case "ALL" -> "ALL";
            default -> "RUNNING";
        };
    }

    private static String instanceStatus(boolean ended, String deleteReason) {
        if (!ended) {
            return "运行中";
        }
        if (deleteReason != null && deleteReason.contains("撤回")) {
            return "已撤回";
        }
        if (deleteReason != null && !deleteReason.isBlank()) {
            return "已终止";
        }
        return "已结束";
    }

    private static String instanceTitle(WorkflowInstanceDetail detail) {
        String name = text(detail.processDefinitionName());
        String key = text(detail.businessKey());
        if (!key.isBlank()) {
            return name.isBlank() ? key : name + " · " + key;
        }
        return name.isBlank() ? text(detail.id()) : name;
    }

    /** activityId → 节点名；已结束实例也查得到，缺名字就退回 id。 */
    private static Map<String, String> nodeNames(WorkflowInstanceDetail detail) {
        Map<String, String> names = new HashMap<>();
        for (WorkflowTaskTrace trace : detail.tasks()) {
            if (trace.activityId() != null && trace.name() != null) {
                names.putIfAbsent(trace.activityId(), trace.name());
            }
        }
        return names;
    }

    private static List<String> mapNodeNames(List<String> activityIds, Map<String, String> names) {
        if (activityIds == null || activityIds.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String id : activityIds) {
            String name = names.getOrDefault(id, id);
            if (!result.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 业务单号反查：先查我发起的，再退到数据范围内的。
     * 未结束优先，避免已结束的历史单盖住同名的新单。
     */
    private List<ProcessInstanceView> findByBusinessKey(CurrentUser user, String businessKey) {
        List<ProcessInstanceView> mine = searchByBusinessKey(businessKey, Set.of(user.userId()));
        if (!mine.isEmpty()) {
            return mine;
        }
        return searchByBusinessKey(businessKey, access.visibleStartUserIds(user));
    }

    private List<ProcessInstanceView> searchByBusinessKey(String businessKey, Set<String> scope) {
        List<ProcessInstanceView> matches = new ArrayList<>();
        for (String state : List.of("RUNNING", "FINISHED")) {
            WorkflowInstancePage page = engine.instancePage(1, 10, state, null, businessKey, scope);
            for (ProcessInstanceView item : page.items()) {
                if (item.businessKey() != null && item.businessKey().equalsIgnoreCase(businessKey)
                        && matches.stream().noneMatch(exists -> exists.id().equals(item.id()))) {
                    matches.add(item);
                }
            }
            if (!matches.isEmpty()) {
                // 未结束的命中就够用，不需要再扫历史
                break;
            }
        }
        return matches;
    }

    private static String timeText(Instant instant) {
        return instant == null ? "" : CARD_TIME.format(instant);
    }

    private static Map<String, Object> stat(String label, long value, String unit, String hint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        item.put("unit", unit);
        item.put("hint", hint);
        return item;
    }

    private static String decisionText(String decision) {
        if (decision == null || decision.isBlank()) {
            return "";
        }
        return switch (decision) {
            case "APPROVE" -> "已同意";
            case "REJECT" -> "已驳回";
            case "ROLLBACK" -> "已回退";
            default -> decision;
        };
    }

    /** 拼接非空片段；全空时返回空串，避免 subtitle 里出现连续的「 ·  · 」。 */
    private static String joinNonBlank(String delimiter, String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(delimiter);
            }
            builder.append(part);
        }
        return builder.toString();
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
