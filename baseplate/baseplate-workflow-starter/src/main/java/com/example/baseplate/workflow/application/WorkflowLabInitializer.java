package com.example.baseplate.workflow.application;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程演练环境：一键建组织/角色/账号/表单/流程，用来验证找人逻辑、会签比例、
 * 网关条件、必填原因以及跨租户隔离。只在用户点「初始化演练环境」时执行。
 */
public final class WorkflowLabInitializer {

    public static final String PROCESS_KEY = "lab-leave";
    public static final String FORM_CODE = "lab_leave_form";
    public static final String EXPENSE_PROCESS_KEY = "lab-expense";
    public static final String EXPENSE_FORM_CODE = "lab_expense_form";
    public static final String LAB_PASSWORD = "Lab@2026!Secure";
    public static final List<String> LAB_USERNAMES = List.of(
            "zhangsan", "lisi", "wangwu", "zhaoliu", "sunqi", "caiwu");

    private final JdbcTemplate jdbc;
    private final WorkflowConfigRepository repository;
    private final ProcessEnginePort engine;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public WorkflowLabInitializer(JdbcTemplate jdbc, WorkflowConfigRepository repository,
                                  ProcessEnginePort engine) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.engine = engine;
    }

    public Map<String, Object> init(String tenant) {
        long techOrg = ensureOrg(tenant, "技术部");
        ensureOrg(tenant, "财务部");
        long employee = ensureRole(tenant, "EMPLOYEE", "员工");
        long manager = ensureRole(tenant, "DEPT_MANAGER", "部门主管");
        long counter = ensureRole(tenant, "COUNTERSIGNER", "会签组成员");
        long finance = ensureRole(tenant, "FINANCE", "财务");

        List<Map<String, Object>> accounts = new ArrayList<>();
        accounts.add(ensureUser(tenant, "zhangsan", "张三（发起人）", techOrg, List.of(employee)));
        accounts.add(ensureUser(tenant, "lisi", "李四（技术部主管）", techOrg, List.of(employee, manager)));
        accounts.add(ensureUser(tenant, "wangwu", "王五（会签）", techOrg, List.of(employee, counter)));
        accounts.add(ensureUser(tenant, "zhaoliu", "赵六（会签）", techOrg, List.of(employee, counter)));
        accounts.add(ensureUser(tenant, "sunqi", "孙七（会签）", techOrg, List.of(employee, counter)));
        accounts.add(ensureUser(tenant, "caiwu", "财务（审批）", techOrg, List.of(finance)));

        ensureForm(tenant);
        ensureExpenseForm(tenant);
        // 幂等：重复点「初始化」不能一直堆新版本
        boolean exists = engine.definitions().stream()
                .anyMatch(item -> PROCESS_KEY.equals(item.key()));
        if (!exists) {
            engine.deploy(LAB_BPMN, PROCESS_KEY, nodeConfigs());
        }
        boolean expenseExists = engine.definitions().stream()
                .anyMatch(item -> EXPENSE_PROCESS_KEY.equals(item.key()));
        if (!expenseExists) {
            engine.deploy(EXPENSE_BPMN, EXPENSE_PROCESS_KEY, expenseNodeConfigs());
        }
        // 发起配置只在缺失时补：管理员后来改过的范围/分类不能被演练初始化覆盖
        ensureSetting(tenant, PROCESS_KEY, "行政办公", "🌴",
                "员工请假申请：部门主管审批；超过 3 天需三人会签（60% 通过），3 天内财务确认。",
                "ROLE", "EMPLOYEE", FORM_CODE, 10);
        ensureSetting(tenant, EXPENSE_PROCESS_KEY, "财务报销", "🧾",
                "日常费用报销：部门主管审批；金额超过 5000 元需财务复核。",
                "ROLE", "EMPLOYEE", EXPENSE_FORM_CODE, 20);

        // 另一个租户只放一个账号，不放流程定义：用来证明「不是本租户就看不到/发起不了」
        long demo2Role = ensureRole("demo2", "EMPLOYEE", "员工");
        ensureUser("demo2", "demo2user", "跨租户验证账号", null, List.of(demo2Role));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant", tenant);
        result.put("processKey", PROCESS_KEY);
        result.put("formCode", FORM_CODE);
        result.put("password", LAB_PASSWORD);
        result.put("accounts", accounts);
        result.put("otherTenant", Map.of("tenant", "demo2", "username", "demo2user",
                "password", LAB_PASSWORD));
        return result;
    }

    private long ensureOrg(String tenant, String name) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM sys_org WHERE tenant_id = ? AND name = ?",
                Long.class, tenant, name);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Long parent = jdbc.queryForObject("SELECT id FROM sys_org WHERE tenant_id = ? ORDER BY id LIMIT 1",
                Long.class, tenant);
        jdbc.update("INSERT INTO sys_org(tenant_id, parent_id, name, path, sort_no)"
                        + " VALUES (?, ?, ?, '/pending', 0)",
                tenant, parent, name);
        long id = jdbc.queryForObject("SELECT id FROM sys_org WHERE tenant_id = ? AND name = ?",
                Long.class, tenant, name);
        jdbc.update("UPDATE sys_org SET path = ? WHERE id = ?",
                (parent == null ? "" : "/" + parent) + "/" + id, id);
        return id;
    }

    private long ensureRole(String tenant, String code, String name) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM sys_role WHERE tenant_id = ? AND role_code = ?",
                Long.class, tenant, code);
        long roleId;
        if (!existing.isEmpty()) {
            roleId = existing.get(0);
        } else {
            jdbc.update("INSERT INTO sys_role(tenant_id, role_code, role_name, data_scope, built_in)"
                    + " VALUES (?, ?, ?, 'SELF', FALSE)", tenant, code, name);
            roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id = ? AND role_code = ?",
                    Long.class, tenant, code);
        }
        grantWorkflowPermissions(roleId);
        return roleId;
    }

    /** 演练账号必须能自己发起和办理，否则「几个新账号测试」只是一堆不能用的登录名。 */
    private void grantWorkflowPermissions(long roleId) {
        for (String permission : List.of("wf:view", "wf:task", "wf:rollback")) {
            jdbc.update("INSERT INTO sys_role_permission(role_id, perm_code) SELECT ?, ?"
                            + " WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission"
                            + " WHERE role_id = ? AND perm_code = ?)",
                    roleId, permission, roleId, permission);
        }
    }

    private Map<String, Object> ensureUser(String tenant, String username, String displayName,
                                           Long orgId, List<Long> roleIds) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id = ? AND username = ?",
                Long.class, tenant, username);
        long userId;
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO sys_user(tenant_id, username, password_hash, display_name,"
                            + " org_id, status, must_change_password, password_changed_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', FALSE, now())",
                    tenant, username, encoder.encode(LAB_PASSWORD), displayName, orgId);
            userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id = ? AND username = ?",
                    Long.class, tenant, username);
        } else {
            userId = existing.get(0);
        }
        for (Long roleId : roleIds) {
            jdbc.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", userId, roleId);
        }
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("username", username);
        account.put("displayName", displayName);
        account.put("tenant", tenant);
        account.put("userId", userId);
        return account;
    }

    private void ensureForm(String tenant) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM meta_schema WHERE tenant_id = ?"
                + " AND kind = 'FORM' AND code = ? AND version = 1", Integer.class, tenant, FORM_CODE);
        if (count != null && count > 0) {
            return;
        }
        String schema = "{\"fields\":[{\"name\":\"days\",\"title\":\"请假天数\",\"type\":\"NUMBER\"},"
                + "{\"name\":\"reason\",\"title\":\"事由\",\"type\":\"TEXT\"},"
                + "{\"name\":\"startDate\",\"title\":\"开始日期\",\"type\":\"DATE\"}]}";
        String rules = "[{\"type\":\"inputNumber\",\"field\":\"days\",\"title\":\"请假天数\","
                + "\"props\":{\"min\":1},\"validate\":[{\"required\":true,\"message\":\"请填写天数\"}]},"
                + "{\"type\":\"textarea\",\"field\":\"reason\",\"title\":\"事由\","
                + "\"validate\":[{\"required\":true,\"message\":\"请填写事由\"}]},"
                + "{\"type\":\"datePicker\",\"field\":\"startDate\",\"title\":\"开始日期\"}]";
        jdbc.update("INSERT INTO meta_schema(tenant_id, kind, code, name, version, status,"
                        + " schema_json, ui_schema_json, published_at)"
                        + " VALUES (?, 'FORM', ?, '请假申请单', 1, 'PUBLISHED', ?::jsonb, ?::jsonb, now())"
                        + " ON CONFLICT (tenant_id, kind, code, version) DO NOTHING",
                tenant, FORM_CODE, schema, rules);
    }

    private void ensureSetting(String tenant, String processKey, String category, String icon,
                               String description, String scopeType, String scopeValue,
                               String formCode, int sortOrder) {
        jdbc.update("INSERT INTO wf_process_setting(tenant_id, process_key, category, description,"
                        + " icon, start_scope_type, start_scope_value, start_form_code, enabled,"
                        + " sort_order, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, 'lab-init')"
                        + " ON CONFLICT (tenant_id, process_key) DO NOTHING",
                tenant, processKey, category, description, icon, scopeType, scopeValue,
                formCode, sortOrder);
    }

    private void ensureExpenseForm(String tenant) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM meta_schema WHERE tenant_id = ?"
                + " AND kind = 'FORM' AND code = ? AND version = 1", Integer.class, tenant, EXPENSE_FORM_CODE);
        if (count != null && count > 0) {
            return;
        }
        String schema = "{\"fields\":[{\"name\":\"amount\",\"title\":\"报销金额\",\"type\":\"MONEY\"},"
                + "{\"name\":\"type\",\"title\":\"报销类型\",\"type\":\"SELECT\"},"
                + "{\"name\":\"reason\",\"title\":\"费用说明\",\"type\":\"TEXT\"},"
                + "{\"name\":\"expenseDate\",\"title\":\"费用日期\",\"type\":\"DATE\"}]}";
        String rules = "[{\"type\":\"inputNumber\",\"field\":\"amount\",\"title\":\"报销金额（元）\","
                + "\"props\":{\"min\":0.01,\"precision\":2},\"validate\":[{\"required\":true,\"message\":\"请填写金额\"}]},"
                + "{\"type\":\"select\",\"field\":\"type\",\"title\":\"报销类型\",\"options\":["
                + "{\"label\":\"差旅费\",\"value\":\"差旅费\"},{\"label\":\"办公用品\",\"value\":\"办公用品\"},"
                + "{\"label\":\"业务招待\",\"value\":\"业务招待\"},{\"label\":\"其他\",\"value\":\"其他\"}],"
                + "\"validate\":[{\"required\":true,\"message\":\"请选择类型\"}]},"
                + "{\"type\":\"textarea\",\"field\":\"reason\",\"title\":\"费用说明\","
                + "\"validate\":[{\"required\":true,\"message\":\"请填写费用说明\"}]},"
                + "{\"type\":\"datePicker\",\"field\":\"expenseDate\",\"title\":\"费用日期\"}]";
        jdbc.update("INSERT INTO meta_schema(tenant_id, kind, code, name, version, status,"
                        + " schema_json, ui_schema_json, published_at)"
                        + " VALUES (?, 'FORM', ?, '费用报销单', 1, 'PUBLISHED', ?::jsonb, ?::jsonb, now())"
                        + " ON CONFLICT (tenant_id, kind, code, version) DO NOTHING",
                tenant, EXPENSE_FORM_CODE, schema, rules);
    }

    private List<WorkflowNodeConfig> expenseNodeConfigs() {
        Map<String, Object> leadButtons = Map.of("buttons", List.of(
                Map.of("code", "APPROVE", "label", "同意", "reasonRequired", false),
                Map.of("code", "REJECT", "label", "驳回", "reasonRequired", true, "reasonMinLength", 5)));
        return List.of(
                new WorkflowNodeConfig("Task_Lead", "部门主管审批", "userTask",
                        "DEPT_MANAGER", "DEPT_MANAGER", "SINGLE", 100, EXPENSE_FORM_CODE,
                        Map.of(), leadButtons, Map.of(), Map.of(), 0),
                new WorkflowNodeConfig("Task_FinanceReview", "财务复核", "userTask",
                        "ROLE", "FINANCE", "SINGLE", 100, EXPENSE_FORM_CODE,
                        Map.of("amount", "READONLY"), Map.of("buttons", List.of(
                                Map.of("code", "APPROVE", "label", "同意", "reasonRequired", false),
                                Map.of("code", "REJECT", "label", "驳回", "reasonRequired", true, "reasonMinLength", 5))),
                        Map.of(), Map.of(), 0),
                new WorkflowNodeConfig("Flow_To_Review", "超过 5000 元财务复核", "sequenceFlow",
                        "INITIATOR", "", "SINGLE", 100, null, Map.of(), Map.of(), Map.of(),
                        Map.of("field", "amount", "operator", ">", "valueType", "NUMBER", "value", 5000), 0),
                new WorkflowNodeConfig("Flow_To_End", "5000 元内直接结束", "sequenceFlow",
                        "INITIATOR", "", "SINGLE", 100, null, Map.of(), Map.of(), Map.of(),
                        Map.of("field", "amount", "operator", "<=", "valueType", "NUMBER", "value", 5000), 0));
    }

    private static final String EXPENSE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:flowable="http://flowable.org/bpmn"
                              id="Definitions_Expense" targetNamespace="http://baseplate">
              <bpmn:process id="lab-expense" name="报销审批（演练）" isExecutable="true">
                <bpmn:startEvent id="Start_Exp" name="提交报销" />
                <bpmn:userTask id="Task_Lead" name="部门主管审批" />
                <bpmn:exclusiveGateway id="Gateway_Amount" name="金额判断" />
                <bpmn:userTask id="Task_FinanceReview" name="财务复核" />
                <bpmn:endEvent id="End_Exp" name="结束" />
                <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_Exp" targetRef="Task_Lead" />
                <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Lead" targetRef="Gateway_Amount" />
                <bpmn:sequenceFlow id="Flow_To_Review" sourceRef="Gateway_Amount" targetRef="Task_FinanceReview" />
                <bpmn:sequenceFlow id="Flow_To_End" sourceRef="Gateway_Amount" targetRef="End_Exp" />
                <bpmn:sequenceFlow id="Flow_5" sourceRef="Task_FinanceReview" targetRef="End_Exp" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    private List<WorkflowNodeConfig> nodeConfigs() {
        Map<String, Object> managerButtons = Map.of("buttons", List.of(
                Map.of("code", "APPROVE", "label", "同意", "reasonRequired", false),
                Map.of("code", "REJECT", "label", "驳回", "reasonRequired", true, "reasonMinLength", 5)));
        return List.of(
                new WorkflowNodeConfig("Task_Manager", "部门主管审批", "userTask",
                        "DEPT_MANAGER", "DEPT_MANAGER", "SINGLE", 100, FORM_CODE,
                        Map.of(), managerButtons, Map.of("onEnter", "NOTIFY"), Map.of(), 0),
                new WorkflowNodeConfig("Task_Countersign", "三人会签", "userTask",
                        "ROLE", "COUNTERSIGNER", "COUNTERSIGN", 60, FORM_CODE,
                        Map.of("days", "READONLY"), Map.of("buttons", List.of(
                                Map.of("code", "APPROVE", "label", "同意", "reasonRequired", false))),
                        Map.of(), Map.of(), 0),
                new WorkflowNodeConfig("Task_Finance", "财务确认", "userTask",
                        "ROLE", "FINANCE", "SINGLE", 100, FORM_CODE,
                        Map.of(), Map.of(), Map.of(), Map.of(), 0),
                new WorkflowNodeConfig("Flow_To_Countersign", "超过 3 天走会签", "sequenceFlow",
                        "INITIATOR", "", "SINGLE", 100, null, Map.of(), Map.of(), Map.of(),
                        Map.of("field", "days", "operator", ">", "valueType", "NUMBER", "value", 3), 0),
                new WorkflowNodeConfig("Flow_To_Finance", "3 天内直接财务", "sequenceFlow",
                        "INITIATOR", "", "SINGLE", 100, null, Map.of(), Map.of(), Map.of(),
                        Map.of("field", "days", "operator", "<=", "valueType", "NUMBER", "value", 3), 0));
    }

    private static final String LAB_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:flowable="http://flowable.org/bpmn"
                              id="Definitions_Lab" targetNamespace="http://baseplate">
              <bpmn:process id="lab-leave" name="请假审批（演练）" isExecutable="true">
                <bpmn:startEvent id="Start_Lab" name="提交申请" />
                <bpmn:userTask id="Task_Manager" name="部门主管审批" />
                <bpmn:exclusiveGateway id="Gateway_Days" name="天数判断" />
                <bpmn:userTask id="Task_Countersign" name="三人会签" />
                <bpmn:userTask id="Task_Finance" name="财务确认" />
                <bpmn:endEvent id="End_Lab" name="结束" />
                <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_Lab" targetRef="Task_Manager" />
                <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Manager" targetRef="Gateway_Days" />
                <bpmn:sequenceFlow id="Flow_To_Countersign" sourceRef="Gateway_Days" targetRef="Task_Countersign" />
                <bpmn:sequenceFlow id="Flow_To_Finance" sourceRef="Gateway_Days" targetRef="Task_Finance" />
                <bpmn:sequenceFlow id="Flow_4" sourceRef="Task_Countersign" targetRef="Task_Finance" />
                <bpmn:sequenceFlow id="Flow_5" sourceRef="Task_Finance" targetRef="End_Lab" />
              </bpmn:process>
            </bpmn:definitions>
            """;
}
