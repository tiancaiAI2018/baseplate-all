package com.example.baseplate.workflow.adapter.out.persistence;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.model.WorkflowProcessSetting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工作流平台化的配置与运行时数据。身份目录只读 sys_* 表，
 * 不反向依赖 identity starter（模块依赖保持单向）。
 */
public final class WorkflowConfigRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WorkflowNodeConfig> NODE_MAPPER = (rs, i) -> new WorkflowNodeConfig(
            rs.getString("node_id"), rs.getString("node_name"), rs.getString("node_type"),
            rs.getString("assignee_type"), rs.getString("assignee_value"),
            rs.getString("sign_type"), rs.getInt("sign_ratio"), rs.getString("form_code"),
            json(rs.getString("field_policy")), json(rs.getString("decision_config")),
            json(rs.getString("trigger_config")), json(rs.getString("condition_config")),
            rs.getInt("timeout_minutes"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper().readValue(value, Map.class);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    public int latestVersion(String tenant, String processKey) {
        Integer value = jdbc.queryForObject("SELECT coalesce(max(definition_version), 0) FROM wf_node_config"
                + " WHERE tenant_id = ? AND process_key = ?", Integer.class, tenant, processKey);
        return value == null ? 0 : value;
    }

    public void saveNodeConfigs(String tenant, String processKey, int version,
                                List<WorkflowNodeConfig> configs) {
        jdbc.update("DELETE FROM wf_node_config WHERE tenant_id = ? AND process_key = ?"
                + " AND definition_version = ?", tenant, processKey, version);
        int order = 0;
        for (WorkflowNodeConfig config : configs) {
            jdbc.update("INSERT INTO wf_node_config(tenant_id, process_key, definition_version,"
                            + " node_id, node_name, node_type, assignee_type, assignee_value,"
                            + " sign_type, sign_ratio, form_code, field_policy, decision_config,"
                            + " trigger_config, condition_config, timeout_minutes, sort_no)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)",
                    tenant, processKey, version, config.nodeId(), config.nodeName(), config.nodeType(),
                    config.assigneeType(), config.assigneeValue(), config.signType(), config.signRatio(),
                    config.formCode(), write(config.fieldPolicy()), write(config.decisionConfig()),
                    write(config.triggerConfig()), write(config.conditionConfig()),
                    config.timeoutMinutes(), order++);
        }
    }

    public List<WorkflowNodeConfig> nodeConfigs(String tenant, String processKey, int version) {
        return jdbc.query("SELECT * FROM wf_node_config WHERE tenant_id = ? AND process_key = ?"
                        + " AND definition_version = ? ORDER BY sort_no, node_id",
                NODE_MAPPER, tenant, processKey, version);
    }

    /** 查定义对应的配置：优先精确版本，找不到时退回草稿（version=0）。 */
    public List<WorkflowNodeConfig> forDefinition(String tenant, String processKey, int version) {
        List<WorkflowNodeConfig> exact = nodeConfigs(tenant, processKey, version);
        if (exact.isEmpty() && version > 0) {
            return nodeConfigs(tenant, processKey, 0);
        }
        return exact;
    }

    public Optional<WorkflowNodeConfig> nodeConfig(String tenant, String processKey,
                                                   int version, String nodeId) {
        return forDefinition(tenant, processKey, version).stream()
                .filter(item -> nodeId.equals(item.nodeId())).findFirst();
    }

    public void saveFormData(String tenant, String instanceId, String nodeId,
                             String formCode, Map<String, Object> data, String operator) {
        jdbc.update("INSERT INTO wf_form_data(tenant_id, instance_id, node_id, form_code,"
                        + " data_json, created_by) VALUES (?, ?, ?, ?, ?::jsonb, ?)"
                        + " ON CONFLICT (tenant_id, instance_id, node_id, form_code)"
                        + " DO UPDATE SET data_json = EXCLUDED.data_json, created_by = EXCLUDED.created_by,"
                        + " created_at = now()",
                tenant, instanceId, nodeId, formCode, write(data == null ? Map.of() : data), operator);
    }

    public List<Map<String, Object>> formData(String tenant, String instanceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        jdbc.query("SELECT node_id, form_code, data_json::text, created_by, created_at"
                        + " FROM wf_form_data WHERE tenant_id = ? AND instance_id = ? ORDER BY id",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("nodeId", rs.getString("node_id"));
                    row.put("formCode", rs.getString("form_code"));
                    row.put("data", json(rs.getString(3)));
                    row.put("createdBy", rs.getString("created_by"));
                    row.put("createdAt", rs.getTimestamp("created_at"));
                    result.add(row);
                }, tenant, instanceId);
        return result;
    }

    public List<Map<String, Object>> widgets(String tenant) {
        return jdbc.queryForList("SELECT id, code, name, category, rule_json::text AS rule_json,"
                        + " description, enabled, version FROM wf_widget"
                        + " WHERE tenant_id = ? ORDER BY category, id", tenant);
    }

    public void saveWidget(String tenant, String code, String name, String category,
                           String ruleJson, String description) {
        jdbc.update("INSERT INTO wf_widget(tenant_id, code, name, category, rule_json, description, version)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?, 1)"
                        + " ON CONFLICT (tenant_id, code, version) DO UPDATE"
                        + " SET name = EXCLUDED.name, category = EXCLUDED.category,"
                        + " rule_json = EXCLUDED.rule_json, description = EXCLUDED.description",
                tenant, code, name, category, ruleJson, description);
    }

    public List<Map<String, Object>> forms(String tenant) {
        return jdbc.queryForList("SELECT DISTINCT ON (code) code, name, version FROM meta_schema"
                + " WHERE tenant_id = ? AND kind = 'FORM' AND status = 'PUBLISHED'"
                + " ORDER BY code, version DESC", tenant);
    }

    /** 最新已发布表单的完整 schema（结构 + form-create 规则），供用户侧发起/办理页渲染。 */
    public Map<String, Object> formSchema(String tenant, String formCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, version, schema_json::text AS schema_json, ui_schema_json::text AS ui_schema_json"
                        + " FROM meta_schema WHERE tenant_id = ? AND kind = 'FORM' AND code = ?"
                        + " AND status = 'PUBLISHED' ORDER BY version DESC LIMIT 1", tenant, formCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- 流程发起配置 ----------

    private static final RowMapper<WorkflowProcessSetting> SETTING_MAPPER = (rs, i) -> new WorkflowProcessSetting(
            rs.getString("process_key"), rs.getString("category"), rs.getString("description"),
            rs.getString("icon"), rs.getString("start_scope_type"), rs.getString("start_scope_value"),
            rs.getString("start_form_code"), rs.getBoolean("enabled"), rs.getInt("sort_order"));

    public Map<String, WorkflowProcessSetting> settings(String tenant) {
        Map<String, WorkflowProcessSetting> result = new LinkedHashMap<>();
        for (WorkflowProcessSetting setting : jdbc.query("SELECT * FROM wf_process_setting"
                + " WHERE tenant_id = ? ORDER BY sort_order, process_key", SETTING_MAPPER, tenant)) {
            result.put(setting.processKey(), setting);
        }
        return result;
    }

    public Optional<WorkflowProcessSetting> setting(String tenant, String processKey) {
        return jdbc.query("SELECT * FROM wf_process_setting WHERE tenant_id = ? AND process_key = ?",
                SETTING_MAPPER, tenant, processKey).stream().findFirst();
    }

    public void saveSetting(String tenant, WorkflowProcessSetting setting, String operator) {
        jdbc.update("INSERT INTO wf_process_setting(tenant_id, process_key, category, description,"
                        + " icon, start_scope_type, start_scope_value, start_form_code, enabled,"
                        + " sort_order, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (tenant_id, process_key) DO UPDATE SET"
                        + " category = EXCLUDED.category, description = EXCLUDED.description,"
                        + " icon = EXCLUDED.icon, start_scope_type = EXCLUDED.start_scope_type,"
                        + " start_scope_value = EXCLUDED.start_scope_value,"
                        + " start_form_code = EXCLUDED.start_form_code, enabled = EXCLUDED.enabled,"
                        + " sort_order = EXCLUDED.sort_order, updated_by = EXCLUDED.updated_by,"
                        + " updated_at = now()",
                tenant, setting.processKey(), setting.category(), setting.description(),
                setting.icon(), setting.startScopeType(), setting.startScopeValue(),
                setting.startFormCode(), setting.enabled(), setting.sortOrder(), operator);
    }

    public List<Map<String, Object>> roles(String tenant) {
        return jdbc.queryForList("SELECT role_code AS code, role_name AS name FROM sys_role"
                + " WHERE tenant_id = ? ORDER BY id", tenant);
    }

    public List<Map<String, Object>> orgs(String tenant) {
        return jdbc.queryForList("SELECT id, name FROM sys_org WHERE tenant_id = ? ORDER BY id", tenant);
    }

    // ---------- 只读身份目录（找人逻辑的数据源） ----------

    public List<String> usersByRole(String tenant, String roleCode) {
        return jdbc.queryForList("SELECT CAST(u.id AS VARCHAR) FROM sys_user u"
                        + " JOIN sys_user_role ur ON ur.user_id = u.id"
                        + " JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE u.tenant_id = ? AND r.tenant_id = ? AND r.role_code = ?"
                        + " AND u.status = 'ACTIVE' ORDER BY u.id",
                String.class, tenant, tenant, roleCode);
    }

    public List<String> usersByOrgRole(String tenant, long orgId, String roleCode) {
        return jdbc.queryForList("SELECT CAST(u.id AS VARCHAR) FROM sys_user u"
                        + " JOIN sys_user_role ur ON ur.user_id = u.id"
                        + " JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE u.tenant_id = ? AND r.tenant_id = ? AND r.role_code = ?"
                        + " AND u.org_id = ? AND u.status = 'ACTIVE' ORDER BY u.id",
                String.class, tenant, tenant, roleCode, orgId);
    }

    public Long orgOfUser(String tenant, String userId) {
        try {
            List<Long> rows = jdbc.queryForList("SELECT org_id FROM sys_user"
                            + " WHERE tenant_id = ? AND CAST(id AS VARCHAR) = ?",
                    Long.class, tenant, userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 数据权限 ORG/ORG_AND_CHILDREN 范围：组织集合内的全部 ACTIVE 用户。 */
    public List<String> userIdsInOrgs(String tenant, Set<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orgIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.addAll(orgIds);
        return jdbc.queryForList("SELECT CAST(id AS VARCHAR) FROM sys_user"
                        + " WHERE tenant_id = ? AND status = 'ACTIVE' AND org_id IN (" + placeholders + ")",
                String.class, args.toArray());
    }

    /** userId → 显示名（无显示名退回用户名），待办/已办列表批量装配发起人。 */
    public Map<String, String> displayNames(String tenant, Collection<String> userIds) {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> ids = userIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.addAll(ids);
        jdbc.query("SELECT CAST(id AS VARCHAR) AS uid, COALESCE(NULLIF(display_name, ''), username) AS name"
                        + " FROM sys_user WHERE tenant_id = ? AND CAST(id AS VARCHAR) IN (" + placeholders + ")",
                rs -> {
                    result.put(rs.getString("uid"), rs.getString("name"));
                }, args.toArray());
        return result;
    }

    /** 转办/加签/发起权限配置用的人员选项：只给最小字段，不暴露手机号等敏感信息。 */
    public List<Map<String, Object>> userOptions(String tenant) {
        return jdbc.queryForList("SELECT CAST(u.id AS VARCHAR) AS \"userId\", u.username,"
                + " COALESCE(NULLIF(u.display_name, ''), u.username) AS \"displayName\","
                + " o.name AS \"orgName\" FROM sys_user u"
                + " LEFT JOIN sys_org o ON o.id = u.org_id"
                + " WHERE u.tenant_id = ? AND u.status = 'ACTIVE' ORDER BY u.id", tenant);
    }

    private String write(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException ex) {
            return "{}";
        }
    }
}
