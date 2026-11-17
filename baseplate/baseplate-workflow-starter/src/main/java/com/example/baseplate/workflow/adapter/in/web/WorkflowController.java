package com.example.baseplate.workflow.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.Decision;
import com.example.baseplate.api.model.FormData;
import com.example.baseplate.api.model.MigrationReport;
import com.example.baseplate.api.model.PageQuery;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.api.model.ProcessDefinitionView;
import com.example.baseplate.api.model.ProcessInstanceView;
import com.example.baseplate.api.model.StartableProcessView;
import com.example.baseplate.api.model.TaskView;
import com.example.baseplate.api.model.WorkflowDefinitionDetail;
import com.example.baseplate.api.model.WorkflowDoneTaskView;
import com.example.baseplate.api.model.WorkflowInstanceDetail;
import com.example.baseplate.api.model.WorkflowInstancePage;
import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.model.WorkflowOverview;
import com.example.baseplate.api.model.WorkflowProcessSetting;
import com.example.baseplate.api.model.WorkflowTaskDetail;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import com.example.baseplate.workflow.application.WorkflowAccessService;
import com.example.baseplate.workflow.application.WorkflowLabInitializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工作流入口：设计、节点配置、版本治理、表单、发起、办理、监控、演练。 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final ProcessEnginePort engine;
    private final CurrentUserProvider currentUser;
    private final WorkflowConfigRepository configRepository;
    private final WorkflowLabInitializer labInitializer;
    private final WorkflowAccessService access;

    public WorkflowController(ProcessEnginePort engine, CurrentUserProvider currentUser,
                              WorkflowConfigRepository configRepository,
                              WorkflowLabInitializer labInitializer,
                              WorkflowAccessService access) {
        this.engine = engine;
        this.currentUser = currentUser;
        this.configRepository = configRepository;
        this.labInitializer = labInitializer;
        this.access = access;
    }

    private String tenant() {
        CurrentUser user = currentUser.current();
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }

    @PostMapping("/definitions")
    @RequiresPermission("wf:deploy")
    @AuditLog(action = "wf:deploy", resource = "process_definition")
    public Map<String, Object> deploy(@RequestBody DeployRequest request) {
        String from = latestDefinitionId(request.processKey());
        List<WorkflowNodeConfig> configs = request.nodeConfigs() == null
                ? List.of() : request.nodeConfigs();
        String deploymentId = engine.deploy(request.bpmnXml(), request.processKey(), configs);
        String to = latestDefinitionId(request.processKey());
        MigrationReport migration = null;
        if ("MIGRATE_ALL".equalsIgnoreCase(request.strategy()) && from != null && to != null
                && !from.equals(to)) {
            migration = engine.migrateInstances(from, to);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deploymentId", deploymentId);
        result.put("processKey", request.processKey());
        result.put("fromDefinitionId", from);
        result.put("toDefinitionId", to);
        // 节点配置是按 (key, version) 存的，部署结果里必须带上版本号，
        // 否则前端/脚本还得再查一次列表才能对上刚存的那套配置
        result.put("version", engine.definitions().stream()
                .filter(item -> request.processKey().equals(item.key()) && item.id().equals(to))
                .map(ProcessDefinitionView::version).findFirst().orElse(null));
        result.put("nodeConfigCount", configs.size());
        result.put("strategy", migration == null ? "KEEP_OLD" : "MIGRATE_ALL");
        result.put("migration", migration);
        return result;
    }

    private String latestDefinitionId(String processKey) {
        return engine.definitions().stream()
                .filter(item -> processKey.equals(item.key()))
                .max(java.util.Comparator.comparingInt(ProcessDefinitionView::version))
                .map(ProcessDefinitionView::id).orElse(null);
    }

    @GetMapping("/definitions")
    @RequiresPermission("wf:view")
    public List<ProcessDefinitionView> definitions() {
        return engine.definitions();
    }

    /** 概览数字（定义数/运行中/已结束/已挂起）：首页只要这几个数，不该拉全部明细。 */
    @GetMapping("/overview")
    @RequiresPermission("wf:view")
    public WorkflowOverview overview() {
        return engine.overview();
    }

    @GetMapping("/definitions/{processDefinitionId}")
    @RequiresPermission("wf:view")
    public WorkflowDefinitionDetail definition(@PathVariable String processDefinitionId) {
        return engine.definitionDetail(processDefinitionId);
    }

    @GetMapping("/definitions/{processDefinitionId}/xml")
    @RequiresPermission("wf:view")
    public Map<String, Object> definitionXml(@PathVariable String processDefinitionId) {
        return Map.of("xml", engine.definitionXml(processDefinitionId));
    }

    @PostMapping("/definitions/{processDefinitionId}/suspend")
    @RequiresPermission("wf:manage")
    @AuditLog(action = "wf:suspend-definition", resource = "process_definition")
    public Map<String, Object> suspend(@PathVariable String processDefinitionId) {
        engine.suspendDefinition(processDefinitionId);
        return Map.of("suspended", true, "processDefinitionId", processDefinitionId);
    }

    @PostMapping("/definitions/{processDefinitionId}/activate")
    @RequiresPermission("wf:manage")
    @AuditLog(action = "wf:activate-definition", resource = "process_definition")
    public Map<String, Object> activate(@PathVariable String processDefinitionId) {
        engine.activateDefinition(processDefinitionId);
        return Map.of("suspended", false, "processDefinitionId", processDefinitionId);
    }

    @GetMapping("/definitions/migrate/preview")
    @RequiresPermission("wf:manage")
    public MigrationReport previewMigration(@RequestParam String from, @RequestParam String to) {
        return engine.validateMigration(from, to);
    }

    @PostMapping("/definitions/migrate")
    @RequiresPermission("wf:manage")
    @AuditLog(action = "wf:migrate", resource = "process_definition")
    public MigrationReport migrate(@RequestBody MigrateRequest request) {
        return engine.migrateInstances(request.fromDefinitionId(), request.toDefinitionId());
    }

    // ---------- 节点配置 ----------

    @GetMapping("/node-configs")
    @RequiresPermission("wf:view")
    public List<WorkflowNodeConfig> nodeConfigs(@RequestParam String processKey,
                                                @RequestParam(defaultValue = "0") int version) {
        String tenant = tenant();
        // version=0 表示「取最新已部署版本」，不是只查草稿
        int target = version > 0 ? version : configRepository.latestVersion(tenant, processKey);
        return configRepository.forDefinition(tenant, processKey, target);
    }

    @PutMapping("/node-configs")
    @RequiresPermission("wf:config")
    @AuditLog(action = "wf:save-node-configs", resource = "wf_node_config")
    public Map<String, Object> saveNodeConfigs(@RequestBody NodeConfigRequest request) {
        configRepository.saveNodeConfigs(tenant(), request.processKey(), 0, request.configs());
        return Map.of("saved", request.configs().size(), "version", 0);
    }

    @GetMapping("/node-configs/options")
    @RequiresPermission("wf:view")
    public Map<String, Object> nodeConfigOptions() {
        String tenant = tenant();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("assigneeTypes", List.of(
                option("INITIATOR", "发起人"), option("USER", "指定用户"),
                option("ROLE", "指定角色"), option("ORG_ROLE", "组织 + 角色"),
                option("DEPT_MANAGER", "发起人部门主管"), option("PREV_OPERATOR", "上一节点办理人"),
                option("FORM_FIELD", "表单字段指定")));
        options.put("signTypes", List.of(
                option("SINGLE", "单签"), option("OR_SIGN", "或签（任一人办理）"),
                option("COUNTERSIGN", "会签（并行）"), option("SEQUENTIAL", "依次签（串行）")));
        options.put("decisionCodes", List.of(
                option("APPROVE", "同意"), option("REJECT", "驳回"),
                option("ROLLBACK", "退回"), option("TRANSFER", "转办")));
        // 触发器目前只做「配置持久化 + 页面展示」，执行器还没接。
        // 文案里必须写清楚，否则用户以为配了就会发通知/调接口——那是骗人。
        options.put("triggers", List.of(
                option("NOTIFY", "发站内通知（执行器未接入）"),
                option("SET_STATUS", "写业务状态（执行器未接入）"),
                option("CALL_API", "调用已登记开放接口（执行器未接入）")));
        options.put("operators", List.of("==", "!=", ">", ">=", "<", "<="));
        options.put("fieldTypes", List.of("STRING", "NUMBER", "MONEY", "DATE", "BOOLEAN", "SELECT"));
        options.put("roles", configRepository.roles(tenant));
        options.put("orgs", configRepository.orgs(tenant));
        options.put("forms", configRepository.forms(tenant));
        return options;
    }

    private static Map<String, Object> option(String value, String label) {
        return Map.of("value", value, "label", label);
    }

    // ---------- 组件素材库 ----------

    @GetMapping("/widgets")
    @RequiresPermission("wf:view")
    public List<Map<String, Object>> widgets() {
        return configRepository.widgets(tenant());
    }

    @PostMapping("/widgets")
    @RequiresPermission("wf:form")
    @AuditLog(action = "wf:save-widget", resource = "wf_widget")
    public Map<String, Object> saveWidget(@RequestBody WidgetRequest request) {
        configRepository.saveWidget(tenant(), request.code(), request.name(),
                request.category(), request.ruleJson(), request.description());
        return Map.of("saved", true, "code", request.code());
    }

    // ---------- 实例与表单 ----------

    /** 「发起流程」目录：最新已发布、未挂起、且当前用户有权发起的流程，附发起表单 schema。 */
    @GetMapping("/catalog")
    @RequiresPermission("wf:task")
    public List<StartableProcessView> catalog() {
        CurrentUser user = currentUser.current();
        String tenant = tenant();
        Map<String, WorkflowProcessSetting> settings = configRepository.settings(tenant);
        Map<String, ProcessDefinitionView> latest = new LinkedHashMap<>();
        for (ProcessDefinitionView definition : engine.definitions()) {
            ProcessDefinitionView existing = latest.get(definition.key());
            if (existing == null || definition.version() > existing.version()) {
                latest.put(definition.key(), definition);
            }
        }
        List<StartableProcessView> result = new ArrayList<>();
        for (ProcessDefinitionView definition : latest.values()) {
            if (definition.suspended()) {
                continue;
            }
            WorkflowProcessSetting setting = settings.get(definition.key());
            if (!access.canStart(user, setting)) {
                continue;
            }
            WorkflowProcessSetting effective = setting == null
                    ? WorkflowProcessSetting.defaults(definition.key()) : setting;
            String formCode = effective.startFormCode();
            if (formCode == null || formCode.isBlank()) {
                formCode = firstFormCode(tenant, definition.key(), definition.version());
            }
            String formSchema = null;
            String formUiSchema = null;
            if (formCode != null && !formCode.isBlank()) {
                Map<String, Object> schema = configRepository.formSchema(tenant, formCode);
                if (schema != null) {
                    formSchema = schema.get("schema_json") == null ? null : String.valueOf(schema.get("schema_json"));
                    formUiSchema = schema.get("ui_schema_json") == null ? null : String.valueOf(schema.get("ui_schema_json"));
                }
            }
            String name = definition.name() == null || definition.name().isBlank()
                    ? definition.key() : definition.name();
            result.add(new StartableProcessView(definition.key(), name, effective.description(),
                    effective.category(), effective.icon(), definition.version(), definition.id(),
                    formCode, formSchema, formUiSchema, effective.sortOrder()));
        }
        result.sort(Comparator.comparingInt(StartableProcessView::sortOrder)
                .thenComparing(StartableProcessView::processKey));
        return result;
    }

    private String firstFormCode(String tenant, String processKey, int version) {
        return configRepository.forDefinition(tenant, processKey, version).stream()
                .filter(config -> "userTask".equals(config.nodeType())
                        && config.formCode() != null && !config.formCode().isBlank())
                .map(WorkflowNodeConfig::formCode).findFirst().orElse(null);
    }

    // ---------- 流程发起配置（发起侧数据权限） ----------

    @GetMapping("/process-settings")
    @RequiresPermission("wf:config")
    public List<WorkflowProcessSetting> processSettings() {
        return new ArrayList<>(configRepository.settings(tenant()).values());
    }

    @PutMapping("/process-settings/{processKey}")
    @RequiresPermission("wf:config")
    @AuditLog(action = "wf:save-process-setting", resource = "wf_process_setting")
    public Map<String, Object> saveProcessSetting(@PathVariable String processKey,
                                                  @RequestBody SettingRequest request) {
        String scopeType = request.startScopeType() == null || request.startScopeType().isBlank()
                ? "ALL" : request.startScopeType();
        if (!List.of("ALL", "ROLE", "USER", "ORG").contains(scopeType)) {
            throw new IllegalArgumentException("发起范围类型不合法: " + scopeType);
        }
        if (!"ALL".equals(scopeType)
                && (request.startScopeValue() == null || request.startScopeValue().isBlank())) {
            throw new IllegalArgumentException("指定发起范围时必须至少选择一个角色/人员/组织");
        }
        WorkflowProcessSetting setting = new WorkflowProcessSetting(processKey,
                request.category() == null || request.category().isBlank() ? "通用" : request.category().trim(),
                request.description() == null ? "" : request.description(),
                request.icon() == null ? "" : request.icon(),
                scopeType,
                request.startScopeValue() == null ? "" : request.startScopeValue(),
                request.startFormCode() == null ? "" : request.startFormCode(),
                request.enabled() == null || request.enabled(),
                request.sortOrder() == null ? 0 : request.sortOrder());
        configRepository.saveSetting(tenant(), setting, currentUser.current().username());
        return Map.of("saved", true, "processKey", processKey);
    }

    /** 转办/加签/发起范围配置的人员选项：普通用户可用，只暴露最小字段。 */
    @GetMapping("/assignee-options")
    @RequiresPermission("wf:task")
    public List<Map<String, Object>> assigneeOptions() {
        return configRepository.userOptions(tenant());
    }

    @PostMapping("/instances")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:start", resource = "process_instance")
    public Map<String, Object> start(@RequestBody StartRequest request) {
        CurrentUser user = currentUser.current();
        String tenant = tenant();
        WorkflowProcessSetting setting = configRepository.setting(tenant, request.processKey()).orElse(null);
        access.requireStart(user, setting, request.processKey());
        Map<String, Object> variables = new LinkedHashMap<>(
                request.variables() == null ? Map.of() : request.variables());
        if (request.formData() != null) {
            variables.put("formData", request.formData());
        }
        String instanceId = engine.start(request.processKey(), request.businessKey(), variables);
        String formCode = request.formCode();
        if (formCode == null || formCode.isBlank()) {
            formCode = setting != null && setting.startFormCode() != null && !setting.startFormCode().isBlank()
                    ? setting.startFormCode()
                    : firstFormCode(tenant, request.processKey(),
                            configRepository.latestVersion(tenant, request.processKey()));
        }
        if (formCode != null && !formCode.isBlank() && request.formData() != null) {
            configRepository.saveFormData(tenant, instanceId, "__START__",
                    formCode, request.formData(), user.username());
        }
        return Map.of("processInstanceId", instanceId);
    }

    @GetMapping("/instances")
    @RequiresPermission("wf:task")
    public List<ProcessInstanceView> instances(@RequestParam(defaultValue = "20") int limit) {
        return engine.instancePage(1, limit, "ALL", null, null,
                access.visibleStartUserIds(currentUser.current())).items();
    }

    @GetMapping("/instances/page")
    @RequiresPermission("wf:task")
    public WorkflowInstancePage instancePage(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(defaultValue = "ALL") String state,
                                             @RequestParam(required = false) String processKey,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "false") boolean mine) {
        CurrentUser user = currentUser.current();
        // mine=true 强制只看自己发起的；否则按角色 data_scope 下传可见发起人集合
        Set<String> startUserIds = mine ? Set.of(user.userId()) : access.visibleStartUserIds(user);
        return engine.instancePage(page, size, state, processKey, keyword, startUserIds);
    }

    @GetMapping("/instances/{processInstanceId}/detail")
    @RequiresPermission({"wf:view", "wf:task"})
    public WorkflowInstanceDetail instanceDetail(@PathVariable String processInstanceId) {
        WorkflowInstanceDetail detail = engine.instanceDetail(processInstanceId);
        access.requireInstanceView(currentUser.current(), processInstanceId, detail.startUserId());
        return detail;
    }

    @GetMapping("/instances/{processInstanceId}/xml")
    @RequiresPermission({"wf:view", "wf:task"})
    public Map<String, Object> instanceXml(@PathVariable String processInstanceId) {
        WorkflowInstanceDetail detail = engine.instanceDetail(processInstanceId);
        access.requireInstanceView(currentUser.current(), processInstanceId, detail.startUserId());
        return Map.of("xml", engine.definitionXml(detail.processDefinitionId()),
                "currentActivities", detail.currentActivities(),
                "completedActivities", detail.completedActivities(),
                "rolledBackActivities", detail.rolledBackActivities());
    }

    @GetMapping("/instances/{processInstanceId}/form-data")
    @RequiresPermission({"wf:view", "wf:task"})
    public List<Map<String, Object>> formData(@PathVariable String processInstanceId) {
        WorkflowInstanceDetail detail = engine.instanceDetail(processInstanceId);
        access.requireInstanceView(currentUser.current(), processInstanceId, detail.startUserId());
        return configRepository.formData(tenant(), processInstanceId);
    }

    @PostMapping("/instances/{processInstanceId}/terminate")
    @RequiresPermission("wf:manage")
    @AuditLog(action = "wf:terminate", resource = "process_instance")
    public Map<String, Object> terminate(@PathVariable String processInstanceId,
                                         @RequestBody(required = false) TerminateRequest request) {
        engine.terminateInstance(processInstanceId, request == null ? null : request.reason());
        return Map.of("terminated", true, "processInstanceId", processInstanceId);
    }

    /** 发起人撤回自己还在运行中的实例；管理员可代撤。 */
    @PostMapping("/instances/{processInstanceId}/withdraw")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:withdraw", resource = "process_instance")
    public Map<String, Object> withdraw(@PathVariable String processInstanceId,
                                        @RequestBody(required = false) TerminateRequest request) {
        CurrentUser user = currentUser.current();
        String startUserId = engine.instanceStartUserId(processInstanceId);
        if (!access.isAdmin(user) && !user.userId().equals(startUserId)) {
            throw new ForbiddenException("只能撤回自己发起的流程");
        }
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "发起人撤回" : "发起人撤回：" + request.reason();
        engine.terminateInstance(processInstanceId, reason);
        return Map.of("withdrawn", true, "processInstanceId", processInstanceId);
    }

    @GetMapping("/instances/{processInstanceId}/tasks")
    @RequiresPermission("wf:task")
    public List<TaskView> instanceTasks(@PathVariable String processInstanceId) {
        String startUserId = engine.instanceStartUserId(processInstanceId);
        access.requireInstanceView(currentUser.current(), processInstanceId, startUserId);
        return engine.tasksOfInstance(processInstanceId);
    }

    // ---------- 任务 ----------

    @GetMapping("/tasks")
    @RequiresPermission("wf:task")
    public PageResult<TaskView> tasks(@RequestParam(required = false) String assignee,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = currentUser.current();
        String target = (assignee == null || assignee.isBlank()) ? user.userId() : assignee;
        // 查别人的待办属于排查能力，只有管理员可用；普通用户一律强制本人
        if (!target.equals(user.userId()) && !access.isAdmin(user)) {
            throw new ForbiddenException("只能查询自己的待办任务");
        }
        PageQuery query = new PageQuery(Math.max(1, page), Math.min(200, Math.max(1, size)), null);
        return new PageResult<>(engine.tasks(target, query), engine.taskCount(target));
    }

    @GetMapping("/tasks/todo")
    @RequiresPermission("wf:task")
    public PageResult<TaskView> todo(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(required = false) String processKey,
                                     @RequestParam(required = false) String keyword) {
        CurrentUser user = currentUser.current();
        PageQuery query = new PageQuery(Math.max(1, page), Math.min(200, Math.max(1, size)), null);
        return new PageResult<>(engine.todo(user, query, processKey, keyword),
                engine.taskCount(user.userId(), processKey, keyword));
    }

    @GetMapping("/tasks/done")
    @RequiresPermission("wf:task")
    public PageResult<WorkflowDoneTaskView> done(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String processKey,
                                                 @RequestParam(required = false) String keyword) {
        CurrentUser user = currentUser.current();
        PageQuery query = new PageQuery(Math.max(1, page), Math.min(200, Math.max(1, size)), null);
        return engine.done(user, query, processKey, keyword);
    }

    /** 办理详情：表单 schema + 已填数据 + 决策按钮 + 可回退节点，一次拿齐。 */
    @GetMapping("/tasks/{taskId}/detail")
    @RequiresPermission("wf:task")
    public WorkflowTaskDetail taskDetail(@PathVariable String taskId) {
        WorkflowTaskDetail detail = engine.taskDetail(taskId);
        if (!detail.canHandle()) {
            access.requireInstanceView(currentUser.current(),
                    detail.task().processInstanceId(), detail.task().startUserId());
        }
        return detail;
    }

    @PostMapping("/tasks/{taskId}/complete")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:complete", resource = "task")
    public Map<String, Object> complete(@PathVariable String taskId,
                                        @RequestBody CompleteRequest request) {
        engine.complete(taskId, request.decision(), new FormData(request.values()));
        return Map.of("completed", true);
    }

    @PostMapping("/tasks/{taskId}/rollback")
    @RequiresPermission("wf:rollback")
    @AuditLog(action = "wf:rollback", resource = "task")
    public Map<String, Object> rollback(@PathVariable String taskId,
                                        @RequestBody RollbackRequest request) {
        engine.rollback(taskId, request.targetNodeId(), request.reason());
        return Map.of("rolledBack", true);
    }

    @PostMapping("/tasks/{taskId}/transfer")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:transfer", resource = "task")
    public Map<String, Object> transfer(@PathVariable String taskId,
                                        @RequestBody TransferRequest request) {
        engine.transfer(taskId, request.toUserId());
        return Map.of("transferred", true);
    }

    @PostMapping("/tasks/{taskId}/add-sign")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:add-sign", resource = "task")
    public Map<String, Object> addSign(@PathVariable String taskId,
                                       @RequestBody AddSignRequest request) {
        engine.addSign(taskId, request.userIds());
        return Map.of("added", true);
    }

    // ---------- 演练环境 ----------

    @PostMapping("/lab/init")
    @RequiresPermission("wf:lab")
    @AuditLog(action = "wf:lab-init", resource = "workflow_lab")
    public Map<String, Object> labInit() {
        return labInitializer.init(tenant());
    }

    @GetMapping("/lab/status")
    @RequiresPermission("wf:lab")
    public Map<String, Object> labStatus() {
        String tenant = tenant();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant", tenant);
        result.put("processKey", WorkflowLabInitializer.PROCESS_KEY);
        result.put("formCode", WorkflowLabInitializer.FORM_CODE);
        result.put("password", WorkflowLabInitializer.LAB_PASSWORD);
        List<ProcessDefinitionView> definitions = engine.definitions().stream()
                .filter(item -> WorkflowLabInitializer.PROCESS_KEY.equals(item.key())).toList();
        result.put("definitions", definitions);
        result.put("initialized", !definitions.isEmpty());
        result.put("accounts", WorkflowLabInitializer.LAB_USERNAMES.stream()
                .map(name -> Map.of("username", name,
                        "password", WorkflowLabInitializer.LAB_PASSWORD, "tenant", tenant))
                .toList());
        result.put("otherTenant", Map.of("tenant", "demo2", "username", "demo2user",
                "password", WorkflowLabInitializer.LAB_PASSWORD));
        return result;
    }

    public record DeployRequest(String bpmnXml, String processKey, String strategy,
                                String fromDefinitionId, List<WorkflowNodeConfig> nodeConfigs) {
    }

    public record MigrateRequest(String fromDefinitionId, String toDefinitionId) {
    }

    public record TerminateRequest(String reason) {
    }

    public record StartRequest(String processKey, String businessKey,
                               Map<String, Object> variables, String formCode,
                               Map<String, Object> formData) {
    }

    public record CompleteRequest(Decision decision, Map<String, Object> values) {
    }

    public record RollbackRequest(String targetNodeId, String reason) {
    }

    public record TransferRequest(String toUserId) {
    }

    public record AddSignRequest(List<String> userIds) {
    }

    public record NodeConfigRequest(String processKey, List<WorkflowNodeConfig> configs) {
    }

    public record WidgetRequest(String code, String name, String category,
                                String ruleJson, String description) {
    }

    public record SettingRequest(String category, String description, String icon,
                                 String startScopeType, String startScopeValue,
                                 String startFormCode, Boolean enabled, Integer sortOrder) {
    }
}
