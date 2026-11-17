package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.Decision;
import com.example.baseplate.api.model.FormData;
import com.example.baseplate.api.model.MigrationReport;
import com.example.baseplate.api.model.PageQuery;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.api.model.ProcessDefinitionView;
import com.example.baseplate.api.model.ProcessInstanceView;
import com.example.baseplate.api.model.TaskView;
import com.example.baseplate.api.model.WorkflowDefinitionDetail;
import com.example.baseplate.api.model.WorkflowDoneTaskView;
import com.example.baseplate.api.model.WorkflowInstanceDetail;
import com.example.baseplate.api.model.WorkflowInstancePage;
import com.example.baseplate.api.model.WorkflowOperationView;
import com.example.baseplate.api.model.WorkflowOverview;
import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.model.WorkflowTaskDetail;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import com.example.baseplate.workflow.application.AssigneeResolver;
import com.example.baseplate.api.model.WorkflowTaskTrace;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Flowable 8 适配器：业务层只认识 ProcessEnginePort，不认识 Flowable 内部类。
 * 换引擎时只改这个类，内核零改动。
 *
 * 历史与运行表的分工（曾经的 bug 来源）：
 * - 运行表（TaskService/TaskQuery）只有「还没办完」的任务，已结束实例查出来必然是空；
 * - 监控与详情一律走 HistoryService，运行表只用来算「当前停在哪」。
 */
public final class FlowableProcessEngineAdapter implements ProcessEnginePort {

    private static final Pattern PROCESS_ID = Pattern.compile(
            "<([a-z0-9_]+:)?process\\b[^>]*\\bid=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final ProcessEngine engine;
    private final BpmnXmlValidator validator;
    private final BpmnDiagramEnricher diagramEnricher;
    private final NodeConfigCompiler compiler;
    private final WorkflowConfigRepository configRepository;
    private final AssigneeResolver assigneeResolver;
    private final WorkflowOperationLogger operations;
    private final CurrentUserProvider currentUser;
    /** 与 Flowable 同库：只用来跑「按定义聚合实例数」这类 Flowable 查询 API 表达不了的 SQL。 */
    private final JdbcTemplate jdbc;

    public FlowableProcessEngineAdapter(ProcessEngine engine, BpmnXmlValidator validator,
                                        BpmnDiagramEnricher diagramEnricher,
                                        NodeConfigCompiler compiler,
                                        WorkflowConfigRepository configRepository,
                                        AssigneeResolver assigneeResolver,
                                        WorkflowOperationLogger operations,
                                        CurrentUserProvider currentUser,
                                        JdbcTemplate jdbc) {
        this.engine = engine;
        this.validator = validator;
        this.diagramEnricher = diagramEnricher;
        this.compiler = compiler;
        this.configRepository = configRepository;
        this.assigneeResolver = assigneeResolver;
        this.operations = operations;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @Override
    public String deploy(String bpmnXml, String processKey, List<WorkflowNodeConfig> nodeConfigs) {
        // 只校验用户上传的原始 XML；编译产物里的表达式/受控监听器都是服务端生成的
        validator.validate(bpmnXml);
        Matcher matcher = PROCESS_ID.matcher(bpmnXml);
        if (matcher.find() && !matcher.group(2).equals(processKey)) {
            throw new IllegalArgumentException("流程 Key 与 XML 中 process id 不一致：页面填的是 "
                    + processKey + "，XML 里是 " + matcher.group(2));
        }
        String tenant = currentTenant();
        List<WorkflowNodeConfig> configs = nodeConfigs == null ? List.of() : nodeConfigs;
        String compiled = compiler.compile(bpmnXml, configs);
        Deployment deployment = engine.getRepositoryService().createDeployment()
                .addString(processKey + ".bpmn20.xml", compiled)
                .disableSchemaValidation()
                .tenantId(tenant)
                .name(processKey)
                .deploy();
        ProcessDefinition definition = engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).processDefinitionTenantId(tenant).singleResult();
        if (definition != null && !configs.isEmpty() && configRepository != null) {
            configRepository.saveNodeConfigs(tenant, processKey, definition.getVersion(), configs);
        }
        return deployment.getId();
    }

    @Override
    public String start(String processKey, String businessKey, Map<String, Object> variables) {
        String tenant = currentTenant();
        RepositoryService repository = engine.getRepositoryService();
        ProcessDefinition definition = repository.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).processDefinitionTenantId(tenant)
                .latestVersion().singleResult();
        if (definition == null) {
            throw new IllegalArgumentException("流程定义不存在: " + processKey);
        }
        if (definition.isSuspended()) {
            throw new IllegalArgumentException("流程已作废，不能发起新实例；老实例不受影响");
        }
        Map<String, Object> vars = new HashMap<>(variables == null ? Map.of() : variables);
        // 待办查询按 userId，不能用 username：否则驳回后「发起人重做」会派给一个查不到的人
        vars.putIfAbsent("initiator", currentUserId());
        vars.putIfAbsent("tenantId", tenant);
        // 表单字段要展开成顶层变量，网关条件（如 ${days > 3}）才能引用
        if (vars.get("formData") instanceof Map<?, ?> formMap) {
            for (Map.Entry<?, ?> entry : formMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    vars.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        // 预计算全部节点的办理人变量：单签引用 wf_assignee_*，会签引用 wf_assignees_*
        if (assigneeResolver != null && configRepository != null) {
            vars.putAll(assigneeResolver.resolveAll(tenant, processKey, definition.getVersion(), vars));
        }
        // Flowable 用线程上下文里的 authenticatedUserId 记录历史表的 start_user_id。
        // 不设置的话 startUserId 为空 → 「我发起的」/数据权限/发起人姓名全部失效。
        // Tomcat 复用线程，必须在 finally 里清掉，避免串到下一个请求。
        ProcessInstance instance;
        Authentication.setAuthenticatedUserId(currentUserId());
        try {
            instance = engine.getRuntimeService()
                    .startProcessInstanceByKeyAndTenantId(processKey, businessKey, vars, tenant);
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
        record("START", instance.getId(), instance.getProcessDefinitionId(), null, null,
                null, "发起流程", null);
        return instance.getId();
    }

    @Override
    public List<TaskView> todo(CurrentUser user, PageQuery page, String processKey, String keyword) {
        return queryTasks(user.userId(), page, processKey, keyword);
    }

    @Override
    public List<TaskView> tasks(String assignee, PageQuery page) {
        return queryTasks(assignee, page, null, null);
    }

    /** 用 candidateOrAssigned：加签后候选人也能在待办里看到，而不是只查直派的 assignee。 */
    private List<TaskView> queryTasks(String assignee, PageQuery page, String processKey, String keyword) {
        int offset = (page.page() - 1) * page.size();
        return enrichTasks(taskQuery(assignee, processKey, keyword)
                .orderByTaskCreateTime().desc()
                .listPage(offset, page.size()));
    }

    private TaskQuery taskQuery(String assignee, String processKey, String keyword) {
        TaskQuery query = engine.getTaskService().createTaskQuery()
                .taskCandidateOrAssigned(assignee)
                .taskTenantId(currentTenant());
        if (processKey != null && !processKey.isBlank()) {
            query.processDefinitionKey(processKey.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            query.or().taskNameLike(like).processInstanceBusinessKeyLike(like).endOr();
        }
        return query;
    }

    @Override
    public PageResult<WorkflowDoneTaskView> done(CurrentUser user, PageQuery page,
                                                 String processKey, String keyword) {
        var query = engine.getHistoryService().createHistoricTaskInstanceQuery()
                .taskAssignee(user.userId())
                .taskTenantId(currentTenant())
                .finished();
        if (processKey != null && !processKey.isBlank()) {
            query.processDefinitionKey(processKey.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            query.or().taskNameLike(like).processInstanceBusinessKeyLike(like).endOr();
        }
        long total = query.count();
        List<HistoricTaskInstance> tasks = query.orderByHistoricTaskInstanceEndTime().desc()
                .listPage((page.page() - 1) * page.size(), page.size());
        if (tasks.isEmpty()) {
            return new PageResult<>(List.of(), total);
        }
        Map<String, HistoricProcessInstance> instances = historicInstances(tasks.stream()
                .map(HistoricTaskInstance::getProcessInstanceId).collect(Collectors.toSet()));
        Map<String, String> names = startUserNames(instances);
        Map<String, WorkflowOperationView> opByTask = new HashMap<>();
        if (operations != null) {
            for (String instanceId : instances.keySet()) {
                for (WorkflowOperationView op : operations.list(instanceId)) {
                    if (op.taskId() != null) {
                        opByTask.put(op.taskId(), op);
                    }
                }
            }
        }
        List<WorkflowDoneTaskView> items = tasks.stream().map(task -> {
            HistoricProcessInstance instance = instances.get(task.getProcessInstanceId());
            WorkflowOperationView op = opByTask.get(task.getId());
            return new WorkflowDoneTaskView(task.getId(), task.getName(), task.getProcessInstanceId(),
                    instance == null ? null : instance.getProcessDefinitionKey(),
                    instance == null ? null : instance.getProcessDefinitionName(),
                    instance == null ? null : instance.getBusinessKey(),
                    task.getTaskDefinitionKey(),
                    instance == null ? null : instance.getStartUserId(),
                    instance == null ? null : names.get(instance.getStartUserId()),
                    instant(task.getStartTime()), instant(task.getEndTime()), task.getDurationInMillis(),
                    op == null ? null : op.decision(), op == null ? null : op.reason());
        }).toList();
        return new PageResult<>(items, total);
    }

    @Override
    public WorkflowTaskDetail taskDetail(String taskId) {
        String tenant = currentTenant();
        Task runtime = engine.getTaskService().createTaskQuery()
                .taskId(taskId).taskTenantId(tenant).singleResult();
        String name;
        String assignee;
        String processInstanceId;
        String processDefinitionId;
        String activityId;
        Instant createdAt;
        boolean ended;
        if (runtime != null) {
            name = runtime.getName();
            assignee = runtime.getAssignee();
            processInstanceId = runtime.getProcessInstanceId();
            processDefinitionId = runtime.getProcessDefinitionId();
            activityId = runtime.getTaskDefinitionKey();
            createdAt = instant(runtime.getCreateTime());
            ended = false;
        } else {
            HistoricTaskInstance historic = engine.getHistoryService().createHistoricTaskInstanceQuery()
                    .taskId(taskId).taskTenantId(tenant).singleResult();
            if (historic == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            name = historic.getName();
            assignee = historic.getAssignee();
            processInstanceId = historic.getProcessInstanceId();
            processDefinitionId = historic.getProcessDefinitionId();
            activityId = historic.getTaskDefinitionKey();
            createdAt = instant(historic.getCreateTime());
            ended = true;
        }
        HistoricProcessInstance instance = engine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        ProcessDefinition definition = engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        WorkflowNodeConfig config = null;
        if (definition != null && configRepository != null) {
            config = configRepository.nodeConfig(tenant, definition.getKey(),
                    definition.getVersion(), activityId).orElse(null);
        }
        // 表单数据按写入顺序合并：发起数据打底，后续节点覆盖同名字段
        Map<String, Object> formData = new LinkedHashMap<>();
        String formCode = config == null ? null : config.formCode();
        if (configRepository != null) {
            for (Map<String, Object> row : configRepository.formData(tenant, processInstanceId)) {
                if (row.get("data") instanceof Map<?, ?> data) {
                    for (Map.Entry<?, ?> entry : data.entrySet()) {
                        if (entry.getKey() != null) {
                            formData.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                }
                if ((formCode == null || formCode.isBlank()) && row.get("formCode") != null) {
                    formCode = String.valueOf(row.get("formCode"));
                }
            }
        }
        String formSchema = null;
        String formUiSchema = null;
        if (formCode != null && !formCode.isBlank() && configRepository != null) {
            Map<String, Object> schema = configRepository.formSchema(tenant, formCode);
            if (schema != null) {
                formSchema = text(schema.get("schema_json"));
                formUiSchema = text(schema.get("ui_schema_json"));
            }
        }
        CurrentUser user = currentUserOrNull();
        boolean canHandle = runtime != null && user != null
                && (isAdmin(user) || user.userId().equals(runtime.getAssignee())
                || isCandidate(taskId, user.userId()));
        List<String> rollbackTargets = new ArrayList<>();
        for (HistoricActivityInstance activity : engine.getHistoryService()
                .createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).activityType("userTask").list()) {
            if (activity.getEndTime() != null && activity.getActivityId() != null
                    && !activity.getActivityId().equals(activityId)
                    && !rollbackTargets.contains(activity.getActivityId())) {
                rollbackTargets.add(activity.getActivityId());
            }
        }
        TaskView view = new TaskView(taskId, name, assignee, processInstanceId,
                instance == null ? null : instance.getProcessDefinitionKey(),
                instance == null ? null : instance.getProcessDefinitionName(),
                instance == null ? null : instance.getBusinessKey(),
                activityId,
                instance == null ? null : instance.getStartUserId(),
                instance == null ? null : startUserNames(Map.of(processInstanceId, instance)).get(instance.getStartUserId()),
                instance == null ? null : instant(instance.getStartTime()), createdAt);
        return new WorkflowTaskDetail(view, config, formCode, formSchema, formUiSchema,
                formData, canHandle, ended, rollbackTargets);
    }

    /** 批量装配任务视图：一次 IN 查询拿实例信息，一次拿发起人显示名，不逐条跨库。 */
    private List<TaskView> enrichTasks(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<String, HistoricProcessInstance> instances = historicInstances(tasks.stream()
                .map(Task::getProcessInstanceId).collect(Collectors.toSet()));
        Map<String, String> names = startUserNames(instances);
        return tasks.stream().map(task -> {
            HistoricProcessInstance instance = instances.get(task.getProcessInstanceId());
            return new TaskView(task.getId(), task.getName(), task.getAssignee(),
                    task.getProcessInstanceId(),
                    instance == null ? null : instance.getProcessDefinitionKey(),
                    instance == null ? null : instance.getProcessDefinitionName(),
                    instance == null ? null : instance.getBusinessKey(),
                    task.getTaskDefinitionKey(),
                    instance == null ? null : instance.getStartUserId(),
                    instance == null ? null : names.get(instance.getStartUserId()),
                    instance == null ? null : instant(instance.getStartTime()),
                    instant(task.getCreateTime()));
        }).toList();
    }

    private Map<String, HistoricProcessInstance> historicInstances(Set<String> instanceIds) {
        Map<String, HistoricProcessInstance> result = new HashMap<>();
        if (instanceIds.isEmpty()) {
            return result;
        }
        for (HistoricProcessInstance instance : engine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceIds(instanceIds).list()) {
            result.put(instance.getId(), instance);
        }
        return result;
    }

    private Map<String, String> startUserNames(Map<String, HistoricProcessInstance> instances) {
        if (configRepository == null) {
            return Map.of();
        }
        return configRepository.displayNames(currentTenant(), instances.values().stream()
                .map(HistoricProcessInstance::getStartUserId).toList());
    }

    @Override
    public void complete(String taskId, Decision decision, FormData data) {
        Task task = requireOwnedTask(taskId);
        Map<String, Object> variables = new HashMap<>();
        if (data != null && data.values() != null) {
            variables.putAll(data.values());
        }
        String resolved = decision == null ? Decision.APPROVE.name() : decision.name();
        variables.put("decision", resolved);
        variables.put("lastOperator", currentUserId());
        // 重新解析一次找人变量：「上一节点办理人」「表单字段指定」依赖本次提交的数据
        String tenant = currentTenant();
        ProcessDefinition definition = engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionId(task.getProcessDefinitionId()).singleResult();
        if (definition != null && assigneeResolver != null && configRepository != null) {
            Map<String, Object> merged = new HashMap<>();
            try {
                merged.putAll(engine.getRuntimeService().getVariables(task.getExecutionId()));
            } catch (RuntimeException ignored) {
                // 拿不到运行变量时不阻塞办理，用本次提交的值继续
            }
            merged.putAll(variables);
            variables.putAll(assigneeResolver.resolveAll(tenant, definition.getKey(),
                    definition.getVersion(), merged));
        }
        String reason = text(variables.get("reason") != null ? variables.get("reason") : variables.get("comment"));
        if (definition != null && configRepository != null) {
            var config = configRepository.nodeConfig(tenant, definition.getKey(),
                    definition.getVersion(), task.getTaskDefinitionKey());
            if (config.isPresent()) {
                validateDecision(config.get(), resolved, reason);
                Object formData = variables.get("formData");
                if (config.get().formCode() != null && !config.get().formCode().isBlank()
                        && formData instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> values = (Map<String, Object>) formData;
                    configRepository.saveFormData(tenant, task.getProcessInstanceId(),
                            task.getTaskDefinitionKey(), config.get().formCode(), values, operator());
                }
            }
        }
        engine.getTaskService().complete(taskId, variables);
        record(resolved, task.getProcessInstanceId(), task.getProcessDefinitionId(), taskId,
                task.getTaskDefinitionKey(), resolved, reason, null);
    }

    @Override
    public void rollback(String taskId, String targetNodeId, String reason) {
        Task task = requireOwnedTask(taskId);
        Set<String> nodes = activityIds(task.getProcessDefinitionId());
        if (targetNodeId == null || !nodes.contains(targetNodeId)) {
            throw new IllegalArgumentException("目标节点不在该流程定义中: " + targetNodeId);
        }
        engine.getRuntimeService().createChangeActivityStateBuilder()
                .processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), targetNodeId)
                .changeState();
        record("ROLLBACK", task.getProcessInstanceId(), task.getProcessDefinitionId(), taskId,
                task.getTaskDefinitionKey(), null, reason, targetNodeId);
    }

    @Override
    public void transfer(String taskId, String toUserId) {
        Task task = requireOwnedTask(taskId);
        if (toUserId == null || toUserId.isBlank()) {
            throw new IllegalArgumentException("转办目标不能为空");
        }
        engine.getTaskService().setAssignee(taskId, toUserId);
        record("TRANSFER", task.getProcessInstanceId(), task.getProcessDefinitionId(), taskId,
                task.getTaskDefinitionKey(), null, "转办给 " + toUserId, null);
    }

    @Override
    public void addSign(String taskId, List<String> userIds) {
        Task task = requireOwnedTask(taskId);
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("加签人不能为空");
        }
        TaskService tasks = engine.getTaskService();
        if (task.getAssignee() != null) {
            tasks.addCandidateUser(taskId, task.getAssignee());
        }
        for (String userId : userIds) {
            tasks.addCandidateUser(taskId, userId);
        }
        // 清空直派，改成候选任务；配合 candidateOrAssigned 查询，加签人真的能看到待办
        tasks.setAssignee(taskId, null);
        record("ADD_SIGN", task.getProcessInstanceId(), task.getProcessDefinitionId(), taskId,
                task.getTaskDefinitionKey(), null, "加签 " + String.join(",", userIds), null);
    }

    @Override
    public long taskCount(String assignee, String processKey, String keyword) {
        return taskQuery(assignee, processKey, keyword).count();
    }

    @Override
    public List<ProcessDefinitionView> definitions() {
        RepositoryService repository = engine.getRepositoryService();
        List<ProcessDefinition> defs = repository.createProcessDefinitionQuery()
                .processDefinitionTenantId(currentTenant())
                .orderByProcessDefinitionKey().asc()
                .orderByProcessDefinitionVersion().desc()
                .list();
        return toViews(defs);
    }

    /**
     * 批量装配定义视图。
     *
     * 旧实现是「每个定义查 3 次」：deploymentTime 一次 + 运行中 count 一次 + 已结束 count 一次。
     * 56 个定义 = 168 次跨库往返，实测 4~5 秒，首页因为拿这个数字卡住整页渲染。
     * 现在压成 2 次往返：部署时间一次 IN 查询，实例计数一条 group by。
     */
    private List<ProcessDefinitionView> toViews(List<ProcessDefinition> defs) {
        if (defs.isEmpty()) {
            return List.of();
        }
        List<String> deploymentIds = defs.stream()
                .map(ProcessDefinition::getDeploymentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Instant> deployTimes = deploymentTimes(deploymentIds);
        Map<String, int[]> counts = instanceCounts(currentTenant());
        return defs.stream()
                .map(definition -> {
                    int[] count = counts.getOrDefault(definition.getId(), new int[]{0, 0});
                    return new ProcessDefinitionView(definition.getId(), definition.getKey(),
                            definition.getName(), definition.getVersion(),
                            deployTimes.get(definition.getDeploymentId()),
                            definition.getDeploymentId(), definition.isSuspended(), hasDiagram(definition),
                            count[0], count[1]);
                })
                .toList();
    }

    private boolean hasDiagram(ProcessDefinition definition) {
        try {
            return definition.hasGraphicalNotation();
        } catch (RuntimeException ignored) {
            // 存量定义可能没有 DI，hasGraphicalNotation 会解析失败；前端靠 definitionXml 自动补布局
            return false;
        }
    }

    private Map<String, Instant> deploymentTimes(List<String> deploymentIds) {
        if (deploymentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Instant> result = new HashMap<>();
        for (Deployment deployment : engine.getRepositoryService().createDeploymentQuery()
                .deploymentIds(deploymentIds).list()) {
            if (deployment.getDeploymentTime() != null) {
                result.put(deployment.getId(), deployment.getDeploymentTime().toInstant());
            }
        }
        return result;
    }

    /**
     * 每个定义下的运行中/已结束实例数，一条 SQL 解决。
     *
     * Flowable 8 的 ManagementService 已经没有 createNativeQuery，
     * 官方替代（executeCustomSql）要求写 MyBatis mapper，为一个 count 不值得；
     * 而本类本来就是 Flowable 专用适配器，同库直查 ACT_* 是成本最低的做法。
     * 换引擎时随本类一起重写即可，业务层无感。
     */
    private Map<String, int[]> instanceCounts(String tenant) {
        // LEFT JOIN 没匹配到实例时会产出一行"全 NULL"，如果只判断 END_TIME_ IS NULL
        // 就会把它算成 1 个运行中实例（56 个定义凭空多出 14 个运行中）。
        // 所以必须先确认这一行真的对应一个实例：h.ID_ IS NOT NULL。
        String sql = """
                SELECT d.ID_,
                       SUM(CASE WHEN h.ID_ IS NOT NULL AND h.END_TIME_ IS NULL THEN 1 ELSE 0 END) AS running_count,
                       SUM(CASE WHEN h.ID_ IS NOT NULL AND h.END_TIME_ IS NOT NULL THEN 1 ELSE 0 END) AS finished_count
                  FROM ACT_RE_PROCDEF d
                  LEFT JOIN ACT_HI_PROCINST h ON h.PROC_DEF_ID_ = d.ID_
                 WHERE d.TENANT_ID_ = ?
                 GROUP BY d.ID_
                """;
        Map<String, int[]> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getString(1),
                    new int[]{rs.getInt(2), rs.getInt(3)});
        }, tenant);
        return result;
    }

    @Override
    public WorkflowOverview overview() {
        String sql = """
                SELECT (SELECT COUNT(*) FROM ACT_RE_PROCDEF WHERE TENANT_ID_ = ?),
                       (SELECT COUNT(*) FROM ACT_RE_PROCDEF WHERE TENANT_ID_ = ? AND SUSPENSION_STATE_ = 2),
                       (SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE TENANT_ID_ = ? AND END_TIME_ IS NULL),
                       (SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE TENANT_ID_ = ? AND END_TIME_ IS NOT NULL)
                """;
        String tenant = currentTenant();
        return jdbc.queryForObject(sql,
                (rs, rowNum) -> new WorkflowOverview(rs.getInt(1), rs.getInt(3), rs.getInt(4), rs.getInt(2)),
                tenant, tenant, tenant, tenant);
    }

    @Override
    public WorkflowDefinitionDetail definitionDetail(String processDefinitionId) {
        ProcessDefinition definition = requireDefinition(processDefinitionId);
        List<ProcessDefinitionView> versions = toViews(engine.getRepositoryService()
                .createProcessDefinitionQuery()
                .processDefinitionKey(definition.getKey())
                .orderByProcessDefinitionVersion().desc()
                .list());
        // 当前版本的运行/结束数直接从批量结果里取，不再单独 count 两次
        ProcessDefinitionView self = versions.stream()
                .filter(item -> item.id().equals(processDefinitionId))
                .findFirst()
                .orElseGet(() -> toViews(List.of(definition)).get(0));
        return new WorkflowDefinitionDetail(definition.getId(), definition.getKey(),
                definition.getName(), definition.getVersion(), definition.getDeploymentId(),
                self.deployedAt(), definition.isSuspended(), self.hasDiagram(),
                definition.getTenantId(), self.runningInstances(), self.finishedInstances(), versions,
                new ArrayList<>(activityIds(processDefinitionId)));
    }

    @Override
    public String definitionXml(String processDefinitionId) {
        ProcessDefinition definition = requireDefinition(processDefinitionId);
        String resource = definition.getResourceName();
        try (InputStream stream = engine.getRepositoryService()
                .getResourceAsStream(definition.getDeploymentId(), resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("找不到流程资源: " + resource);
            }
            return diagramEnricher.ensureDiagram(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取流程定义 XML 失败", ex);
        }
    }

    @Override
    public WorkflowInstanceDetail instanceDetail(String processInstanceId) {
        HistoricProcessInstance instance = engine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (instance == null || !currentTenant().equals(emptyToDefault(instance.getTenantId()))) {
            throw new IllegalArgumentException("流程实例不存在: " + processInstanceId);
        }
        ProcessDefinition definition = engine.getRepositoryService()
                .createProcessDefinitionQuery().processDefinitionId(instance.getProcessDefinitionId())
                .singleResult();
        int version = instance.getProcessDefinitionVersion() == null
                ? (definition == null ? 0 : definition.getVersion())
                : instance.getProcessDefinitionVersion();

        List<HistoricActivityInstance> activities = engine.getHistoryService()
                .createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).list();
        activities.sort(Comparator.comparing(HistoricActivityInstance::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Set<String> completed = new LinkedHashSet<>();
        Set<String> current = new LinkedHashSet<>();
        for (HistoricActivityInstance activity : activities) {
            if (activity.getActivityId() == null || "sequenceFlow".equals(activity.getActivityType())) {
                continue;
            }
            if (activity.getEndTime() == null) {
                current.add(activity.getActivityId());
            } else {
                completed.add(activity.getActivityId());
            }
        }

        List<WorkflowTaskTrace> traces = taskTraces(processInstanceId);
        Map<String, Object> variables = new LinkedHashMap<>();
        try {
            List<HistoricVariableInstance> vars = engine.getHistoryService()
                    .createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).list();
            vars.sort(Comparator.comparing(HistoricVariableInstance::getLastUpdatedTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (HistoricVariableInstance var : vars) {
                variables.put(var.getVariableName(), var.getValue());
            }
        } catch (RuntimeException ignored) {
            // 历史级别较低时变量可能查不到，详情其余部分仍然可用
        }

        List<WorkflowOperationView> ops = operations == null ? List.of() : operations.list(processInstanceId);
        // 「最近原因」专指最近一次打回（驳回 / 回退）的原因。
        // 早期的实现取任意最新操作，结果驳回原因会被后面的「同意」意见覆盖，
        // 管理员在监控页看到的就是错的。
        String lastReason = null;
        String lastDecision = null;
        for (int i = ops.size() - 1; i >= 0; i--) {
            WorkflowOperationView op = ops.get(i);
            boolean rollback = "ROLLBACK".equals(op.action()) || "REJECT".equals(op.decision());
            if (rollback && op.reason() != null && !op.reason().isBlank()) {
                lastReason = op.reason();
                lastDecision = op.decision();
                break;
            }
        }
        // 没有打回记录时保持 null，不要退回到「任意最新一条有 reason 的操作」：
        // 那样详情里「最近打回原因」会显示成「发起流程」这种无关内容。
        int rollbackCount = operations == null ? 0 : operations.rollbackCount(processInstanceId);
        List<String> rolledBack = operations == null ? List.of()
                : operations.rolledBackActivities(processInstanceId);

        return new WorkflowInstanceDetail(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getProcessDefinitionName(), version,
                instance.getBusinessKey(), instance.getStartUserId(), instant(instance.getStartTime()),
                instant(instance.getEndTime()), instance.getEndTime() != null,
                instance.getDeleteReason(), instance.getDurationInMillis(),
                new ArrayList<>(current), new ArrayList<>(completed), rolledBack,
                rollbackCount, lastReason, lastDecision, traces, variables, ops);
    }

    /** 已结束实例也必须能看到全部任务：这里查的是历史任务表，不是运行任务表。 */
    private List<WorkflowTaskTrace> taskTraces(String processInstanceId) {
        List<HistoricTaskInstance> tasks = engine.getHistoryService()
                .createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).list();
        tasks.sort(Comparator.comparing(HistoricTaskInstance::getStartTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, String> reasons = new HashMap<>();
        Map<String, String> decisions = new HashMap<>();
        if (operations != null) {
            for (WorkflowOperationView op : operations.list(processInstanceId)) {
                if (op.taskId() != null && op.reason() != null) {
                    reasons.put(op.taskId(), op.reason());
                }
                if (op.taskId() != null && op.decision() != null) {
                    decisions.put(op.taskId(), op.decision());
                }
            }
        }
        return tasks.stream().map(task -> new WorkflowTaskTrace(
                task.getId(), task.getName(), task.getAssignee(), task.getTaskDefinitionKey(),
                instant(task.getStartTime()), instant(task.getEndTime()),
                task.getDurationInMillis(), decisions.get(task.getId()), reasons.get(task.getId()),
                task.getDeleteReason(), task.getEndTime() != null)).toList();
    }

    @Override
    public List<TaskView> tasksOfInstance(String processInstanceId) {
        return enrichTasks(engine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskTenantId(currentTenant())
                .orderByTaskCreateTime().asc().list());
    }

    @Override
    public List<ProcessInstanceView> instances(int limit) {
        return instancePage(1, limit, "ALL", null, null, null).items();
    }

    @Override
    public WorkflowInstancePage instancePage(int page, int size, String state,
                                             String processKey, String keyword,
                                             Set<String> startUserIds) {
        // 空集合 = 数据范围内没有任何发起人：直接空页，不下发无过滤查询
        if (startUserIds != null && startUserIds.isEmpty()) {
            return new WorkflowInstancePage(List.of(), 0);
        }
        int pageNo = Math.max(1, page);
        int limit = Math.min(200, Math.max(1, size));
        var query = engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceTenantId(currentTenant());
        if ("RUNNING".equalsIgnoreCase(state)) {
            query.unfinished();
        } else if ("FINISHED".equalsIgnoreCase(state)) {
            query.finished();
        }
        if (processKey != null && !processKey.isBlank()) {
            query.processDefinitionKey(processKey.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            query.processInstanceBusinessKeyLike("%" + keyword.trim() + "%");
        }
        if (startUserIds != null) {
            if (startUserIds.size() == 1) {
                query.startedBy(startUserIds.iterator().next());
            } else {
                query.or();
                for (String userId : startUserIds) {
                    query.startedBy(userId);
                }
                query.endOr();
            }
        }
        long total = query.count();
        List<HistoricProcessInstance> history = query.orderByProcessInstanceStartTime().desc()
                .listPage((pageNo - 1) * limit, limit);
        if (history.isEmpty()) {
            return new WorkflowInstancePage(List.of(), total);
        }
        List<String> ids = history.stream().map(HistoricProcessInstance::getId).toList();
        Map<String, List<String>> tasksByInstance = new LinkedHashMap<>();
        for (Task task : engine.getTaskService().createTaskQuery()
                .processInstanceIdIn(ids).list()) {
            tasksByInstance.computeIfAbsent(task.getProcessInstanceId(), key -> new ArrayList<>())
                    .add(task.getName());
        }
        Map<String, Integer> rollbackCounts = rollbackCounts(ids);
        return new WorkflowInstancePage(history.stream().map(instance -> new ProcessInstanceView(
                        instance.getId(), instance.getProcessDefinitionKey(),
                        instance.getProcessDefinitionName(), instance.getBusinessKey(),
                        instance.getStartUserId(), instant(instance.getStartTime()),
                        instant(instance.getEndTime()), instance.getEndTime() != null,
                        tasksByInstance.getOrDefault(instance.getId(), List.of()),
                        instance.getProcessDefinitionId(),
                        instance.getProcessDefinitionVersion() == null ? 0 : instance.getProcessDefinitionVersion(),
                        rollbackCounts.getOrDefault(instance.getId(), 0), instance.getDeleteReason()))
                .toList(), total);
    }

    private Map<String, Integer> rollbackCounts(List<String> instanceIds) {
        Map<String, Integer> result = new HashMap<>();
        if (operations == null) {
            return result;
        }
        for (String id : instanceIds) {
            result.put(id, operations.rollbackCount(id));
        }
        return result;
    }

    @Override
    public List<WorkflowOperationView> operations(String processInstanceId) {
        return operations == null ? List.of() : operations.list(processInstanceId);
    }

    @Override
    public void suspendDefinition(String processDefinitionId) {
        ProcessDefinition definition = requireDefinition(processDefinitionId);
        // 第二个参数 false = 不挂起已有实例：新实例发起被引擎拒绝，老实例照常走完
        engine.getRepositoryService().suspendProcessDefinitionById(processDefinitionId, false, null);
        record("SUSPEND", null, processDefinitionId, null, null, null,
                "作废 " + definition.getKey() + " v" + definition.getVersion(), null);
    }

    @Override
    public void activateDefinition(String processDefinitionId) {
        ProcessDefinition definition = requireDefinition(processDefinitionId);
        engine.getRepositoryService().activateProcessDefinitionById(processDefinitionId, false, null);
        record("ACTIVATE", null, processDefinitionId, null, null, null,
                "恢复启用 " + definition.getKey() + " v" + definition.getVersion(), null);
    }

    @Override
    public MigrationReport validateMigration(String fromDefinitionId, String toDefinitionId) {
        ProcessDefinition from = requireDefinition(fromDefinitionId);
        ProcessDefinition to = requireDefinition(toDefinitionId);
        List<String> issues = new ArrayList<>();
        if (!from.getKey().equals(to.getKey())) {
            issues.add("两个定义不属于同一个流程 Key（" + from.getKey() + " / " + to.getKey() + "），不能迁移");
        }
        if (to.isSuspended()) {
            issues.add("目标版本已作废，请先恢复启用");
        }
        List<HistoricProcessInstance> running = runningInstances(fromDefinitionId);
        Set<String> targetNodes = activityIds(toDefinitionId);
        for (HistoricProcessInstance instance : running) {
            for (String node : currentActivityIds(instance.getId())) {
                if (!targetNodes.contains(node)) {
                    issues.add("实例 " + (instance.getBusinessKey() == null
                                    ? instance.getId() : instance.getBusinessKey())
                            + " 当前停在 " + node + "，新版本里没有这个节点");
                }
            }
        }
        return new MigrationReport(issues.isEmpty(), running.size(), fromDefinitionId, toDefinitionId, issues);
    }

    @Override
    public MigrationReport migrateInstances(String fromDefinitionId, String toDefinitionId) {
        MigrationReport report = validateMigration(fromDefinitionId, toDefinitionId);
        if (!report.canMigrate()) {
            record("MIGRATE_REJECTED", null, fromDefinitionId, null, null, null,
                    "整体拒绝：" + String.join("；", report.issues()), toDefinitionId);
            return report;
        }
        List<HistoricProcessInstance> running = runningInstances(fromDefinitionId);
        try {
            engine.getProcessMigrationService().createProcessInstanceMigrationBuilder()
                    .migrateToProcessDefinition(toDefinitionId)
                    .migrateProcessInstances(fromDefinitionId);
        } catch (RuntimeException ex) {
            List<String> issues = new ArrayList<>(report.issues());
            issues.add("引擎拒绝迁移：" + ex.getMessage());
            return new MigrationReport(false, running.size(), fromDefinitionId, toDefinitionId, issues);
        }
        for (HistoricProcessInstance instance : running) {
            record("MIGRATE", instance.getId(), fromDefinitionId, null, null, null,
                    "从 v" + requireDefinition(fromDefinitionId).getVersion()
                            + " 迁移到 v" + requireDefinition(toDefinitionId).getVersion()
                            + "，保留当前节点", toDefinitionId);
        }
        return new MigrationReport(true, running.size(), fromDefinitionId, toDefinitionId, List.of());
    }

    @Override
    public void terminateInstance(String processInstanceId, String reason) {
        HistoricProcessInstance instance = engine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (instance == null || instance.getEndTime() != null) {
            throw new IllegalArgumentException("实例不存在或已结束: " + processInstanceId);
        }
        engine.getRuntimeService().deleteProcessInstance(processInstanceId,
                reason == null || reason.isBlank() ? "管理员终止" : reason);
        record("TERMINATE", processInstanceId, instance.getProcessDefinitionId(), null, null,
                null, reason, null);
    }

    private List<HistoricProcessInstance> runningInstances(String processDefinitionId) {
        return engine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionId(processDefinitionId).unfinished().list();
    }

    private Set<String> currentActivityIds(String processInstanceId) {
        Set<String> ids = new LinkedHashSet<>();
        for (HistoricActivityInstance activity : engine.getHistoryService()
                .createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).list()) {
            if (activity.getEndTime() == null && activity.getActivityId() != null) {
                ids.add(activity.getActivityId());
            }
        }
        return ids;
    }

    /** 迁移预检要用「目标版本到底有哪些节点」，而不是靠猜节点 ID 命名规律。 */
    private Set<String> activityIds(String processDefinitionId) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            BpmnModel model = engine.getRepositoryService().getBpmnModel(processDefinitionId);
            for (Process process : model.getProcesses()) {
                for (FlowElement element : process.getFlowElements()) {
                    ids.add(element.getId());
                }
            }
        } catch (RuntimeException ignored) {
            // 解析不了时返回空集合：迁移预检会把它当成「全部不兼容」，属于保守失败
        }
        return ids;
    }

    private ProcessDefinition requireDefinition(String processDefinitionId) {
        ProcessDefinition definition = engine.getRepositoryService()
                .createProcessDefinitionQuery().processDefinitionId(processDefinitionId).singleResult();
        if (definition == null || !currentTenant().equals(emptyToDefault(definition.getTenantId()))) {
            throw new IllegalArgumentException("流程定义不存在: " + processDefinitionId);
        }
        return definition;
    }

    private Task requireTask(String taskId) {
        Task task = engine.getTaskService().createTaskQuery()
                .taskId(taskId).taskTenantId(currentTenant()).singleResult();
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    /** 办理类操作必须校验归属：直派人、候选人或管理员，其他人一律 403。 */
    private Task requireOwnedTask(String taskId) {
        Task task = requireTask(taskId);
        CurrentUser user = currentUserOrNull();
        if (user == null || isAdmin(user)
                || user.userId().equals(task.getAssignee())
                || isCandidate(taskId, user.userId())) {
            return task;
        }
        throw new ForbiddenException("只能办理自己的待办任务");
    }

    private boolean isCandidate(String taskId, String userId) {
        return engine.getTaskService().getIdentityLinksForTask(taskId).stream()
                .anyMatch(link -> "candidate".equals(link.getType()) && userId.equals(link.getUserId()));
    }

    private static boolean isAdmin(CurrentUser user) {
        return user.roles().contains("SUPER_ADMIN") || user.permissions().contains("wf:manage");
    }

    @Override
    public String instanceStartUserId(String processInstanceId) {
        HistoricProcessInstance instance = engine.getHistoryService()
                .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (instance == null || !currentTenant().equals(emptyToDefault(instance.getTenantId()))) {
            throw new IllegalArgumentException("流程实例不存在: " + processInstanceId);
        }
        return instance.getStartUserId();
    }

    @Override
    public boolean isParticipant(String processInstanceId, String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        long handled = engine.getHistoryService().createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).taskAssignee(userId).count();
        if (handled > 0) {
            return true;
        }
        return engine.getTaskService().createTaskQuery()
                .processInstanceId(processInstanceId).taskCandidateUser(userId).count() > 0;
    }

    private Instant deploymentTime(String deploymentId) {
        try {
            Deployment deployment = engine.getRepositoryService().createDeploymentQuery()
                    .deploymentId(deploymentId).singleResult();
            return deployment == null || deployment.getDeploymentTime() == null
                    ? null : deployment.getDeploymentTime().toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String operator() {
        if (currentUser == null) {
            return "system";
        }
        try {
            CurrentUser user = currentUser.current();
            return user == null ? "system" : String.valueOf(user.username());
        } catch (RuntimeException ex) {
            return "system";
        }
    }

    private CurrentUser currentUserOrNull() {
        if (currentUser == null) {
            return null;
        }
        try {
            return currentUser.current();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String emptyToDefault(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private String currentTenant() {
        if (currentUser == null) {
            return "default";
        }
        try {
            CurrentUser user = currentUser.current();
            return user == null ? "default" : emptyToDefault(user.tenantId());
        } catch (RuntimeException ex) {
            return "default";
        }
    }

    private String currentUserId() {
        if (currentUser == null) {
            return "system";
        }
        try {
            CurrentUser user = currentUser.current();
            return user == null ? "system" : String.valueOf(user.userId());
        } catch (RuntimeException ex) {
            return "system";
        }
    }

    private void record(String action, String instanceId, String definitionId, String taskId,
                        String activityId, String decision, String reason, String targetNodeId) {
        if (operations != null) {
            operations.record(action, instanceId, definitionId, taskId, activityId,
                    decision, reason, operator(), targetNodeId);
        }
    }

    /** 决策按钮的必填原因与最少字数由节点配置决定，服务端必须再校验一次。 */
    private static void validateDecision(WorkflowNodeConfig config, String decision, String reason) {
        Object buttons = config.decisionConfig().get("buttons");
        if (!(buttons instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> button)
                    || !decision.equals(String.valueOf(button.get("code")))) {
                continue;
            }
            boolean required = Boolean.parseBoolean(String.valueOf(button.get("reasonRequired")));
            int min = 0;
            try {
                Object minValue = button.get("reasonMinLength");
                min = Integer.parseInt(String.valueOf(minValue == null ? 0 : minValue));
            } catch (NumberFormatException ignored) {
                min = 0;
            }
            if (required && (reason == null || reason.isBlank())) {
                Object label = button.get("label");
                throw new IllegalArgumentException("「" + (label == null ? decision : label) + "」必须填写原因");
            }
            if (min > 0 && reason != null && reason.trim().length() < min) {
                throw new IllegalArgumentException("原因至少 " + min + " 个字");
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
