package com.example.baseplate.agent.adapter.out.persistence;

import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.tool.ToolDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

/**
 * 智能体配置持久化。显式 SQL + JSONB，和项目其它 starter 的写法保持一致。
 *
 * <p>API Key 只以密文进出（{@code api_key_enc}），更新时不传则保留旧值，
 * 这样「编辑其他字段」不会误清空密钥。</p>
 */
public final class AgentDefinitionRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, agent_code, name, description, provider, model_name, base_url,
                   api_key_enc, system_prompt, model_params, allowed_pages, status, revision
            FROM agent_definition
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建/编辑入参；apiKeyEnc 为 null 表示「不修改」。 */
    public record DefinitionInput(
            String agentCode,
            String name,
            String description,
            String provider,
            String modelName,
            String baseUrl,
            String apiKeyEnc,
            String systemPrompt,
            Map<String, Object> modelParams,
            List<String> allowedPages,
            String status) {
    }

    public record ToolBinding(String toolCode, boolean enabled, ApprovalMode approvalMode) {
    }

    /** 技能绑定：技能正文在本地目录，这里只记启停。 */
    public record SkillBinding(String skillName, boolean enabled) {
    }

    public record ToolCatalogRow(
            String toolCode,
            String name,
            String description,
            ToolType toolType,
            boolean builtin,
            boolean editable,
            Set<String> pages,
            String permissionCode,
            RiskLevel riskLevel,
            ApprovalMode minApproval,
            ApprovalMode defaultApproval,
            Map<String, Object> paramsSchema,
            Map<String, Object> config,
            String groupName) {
    }

    /** 控制台创建自定义工具（支持 EXTERNAL / CUSTOM_INTERNAL）的入参。 */
    public record CustomToolInput(
            String toolCode,
            String name,
            String description,
            ToolType toolType,
            Set<String> pages,
            String permissionCode,
            RiskLevel riskLevel,
            ApprovalMode minApproval,
            ApprovalMode defaultApproval,
            Map<String, Object> paramsSchema,
            Map<String, Object> config,
            String groupName) {
    }

    private final RowMapper<AgentDefinition> DEFINITION_MAPPER = (rs, i) -> new AgentDefinition(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("agent_code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("provider"),
            rs.getString("model_name"),
            rs.getString("base_url"),
            rs.getString("api_key_enc"),
            rs.getString("system_prompt"),
            readMap(rs.getString("model_params")),
            readStringList(rs.getString("allowed_pages")),
            rs.getString("status"),
            rs.getInt("revision"));

    public List<AgentDefinition> list(String tenant) {
        return jdbc.query(SELECT_COLUMNS + " WHERE tenant_id = ? ORDER BY id",
                DEFINITION_MAPPER, tenant);
    }

    public Optional<AgentDefinition> find(String tenant, long id) {
        return jdbc.query(SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ?",
                DEFINITION_MAPPER, tenant, id).stream().findFirst();
    }

    public Optional<AgentDefinition> findByCode(String tenant, String code) {
        return jdbc.query(SELECT_COLUMNS + " WHERE tenant_id = ? AND agent_code = ?",
                DEFINITION_MAPPER, tenant, code).stream().findFirst();
    }

    public long insert(String tenant, DefinitionInput input) {
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_definition
                    (tenant_id, agent_code, name, description, provider, model_name, base_url,
                     api_key_enc, system_prompt, model_params, allowed_pages, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING id
                """, Long.class,
                tenant, input.agentCode(), input.name(), input.description(),
                input.provider(), input.modelName(), input.baseUrl(), input.apiKeyEnc(),
                input.systemPrompt(), writeJson(input.modelParams()), writeJson(input.allowedPages()),
                input.status() == null ? "ENABLED" : input.status());
        return id == null ? 0L : id;
    }

    public void update(String tenant, long id, DefinitionInput input) {
        jdbc.update("""
                UPDATE agent_definition
                   SET name = ?, description = ?, provider = ?, model_name = ?, base_url = ?,
                       api_key_enc = COALESCE(?, api_key_enc),
                       system_prompt = ?, model_params = ?::jsonb, allowed_pages = ?::jsonb,
                       status = ?, revision = revision + 1, updated_at = now()
                 WHERE tenant_id = ? AND id = ?
                """,
                input.name(), input.description(), input.provider(), input.modelName(),
                input.baseUrl(), input.apiKeyEnc(), input.systemPrompt(),
                writeJson(input.modelParams()), writeJson(input.allowedPages()),
                input.status(), tenant, id);
    }

    public void deleteDependents(String tenant, long id) {
        jdbc.update("DELETE FROM agent_hitl_request WHERE tenant_id = ? AND agent_id = ?", tenant, id);
        jdbc.update("DELETE FROM agent_external_call WHERE tenant_id = ? AND agent_id = ?", tenant, id);
    }

    public void delete(String tenant, long id) {
        jdbc.update("DELETE FROM agent_definition WHERE tenant_id = ? AND id = ?", tenant, id);
    }

    public List<ToolBinding> toolBindings(String tenant, long agentId) {
        return jdbc.query("""
                SELECT tool_code, enabled, approval_mode
                  FROM agent_definition_tool
                 WHERE tenant_id = ? AND agent_id = ?
                 ORDER BY tool_code
                """, (rs, i) -> new ToolBinding(
                rs.getString("tool_code"),
                rs.getBoolean("enabled"),
                ApprovalMode.valueOf(rs.getString("approval_mode"))), tenant, agentId);
    }

    public void upsertToolBinding(String tenant, long agentId, String toolCode,
                                  boolean enabled, ApprovalMode approvalMode) {
        jdbc.update("""
                INSERT INTO agent_definition_tool (tenant_id, agent_id, tool_code, enabled, approval_mode)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (agent_id, tool_code)
                DO UPDATE SET enabled = EXCLUDED.enabled,
                              approval_mode = EXCLUDED.approval_mode,
                              updated_at = now()
                """, tenant, agentId, toolCode, enabled, approvalMode.name());
    }

    /** 返回全部绑定（含停用）；空列表表示这个 Agent 还没配置过技能。 */
    public List<SkillBinding> skillBindings(String tenant, long agentId) {
        return jdbc.query("""
                SELECT skill_name, enabled
                  FROM agent_definition_skill
                 WHERE tenant_id = ? AND agent_id = ?
                 ORDER BY skill_name
                """, (rs, i) -> new SkillBinding(rs.getString("skill_name"), rs.getBoolean("enabled")),
                tenant, agentId);
    }

    /** 全量替换：页面保存的是完整勾选结果，先删后插比逐条 diff 更不容易漏状态。 */
    public void replaceSkillBindings(String tenant, long agentId, List<SkillBinding> bindings) {
        jdbc.update("DELETE FROM agent_definition_skill WHERE tenant_id = ? AND agent_id = ?",
                tenant, agentId);
        for (SkillBinding binding : bindings) {
            jdbc.update("""
                    INSERT INTO agent_definition_skill (tenant_id, agent_id, skill_name, enabled)
                    VALUES (?, ?, ?, ?)
                    """, tenant, agentId, binding.skillName(), binding.enabled());
        }
    }

    /** 内置工具目录同步：按 code upsert，editable 永远为 false，运营不能改描述或删除。 */
    public void syncToolCatalog(List<ToolDescriptor> descriptors) {
        for (ToolDescriptor descriptor : descriptors) {
            jdbc.update("""
                    INSERT INTO agent_tool_definition
                        (tenant_id, tool_code, name, description, tool_type, builtin, editable,
                         pages, permission_code, risk_level, min_approval, default_approval, status)
                    VALUES ('*', ?, ?, ?, ?, TRUE, FALSE, ?::jsonb, ?, ?, ?, ?, 'ENABLED')
                    ON CONFLICT (tenant_id, tool_code)
                    DO UPDATE SET name = EXCLUDED.name,
                                  description = EXCLUDED.description,
                                  tool_type = EXCLUDED.tool_type,
                                  builtin = TRUE,
                                  editable = FALSE,
                                  pages = EXCLUDED.pages,
                                  permission_code = EXCLUDED.permission_code,
                                  risk_level = EXCLUDED.risk_level,
                                  min_approval = EXCLUDED.min_approval,
                                  default_approval = EXCLUDED.default_approval,
                                  updated_at = now()
                    """,
                    descriptor.code(), descriptor.name(), descriptor.description(),
                    descriptor.type().name(), writeJson(descriptor.pages()),
                    descriptor.permissionCode(), descriptor.risk().name(),
                    descriptor.minApproval().name(), descriptor.defaultApproval().name());
        }
        // 只 upsert 不删除会留下「幽灵工具」：代码里已经删掉的工具仍然出现在配置页，
        // 模型也可能被喂到不存在的工具名。内置目录以代码为准，多余的直接清掉。
        if (descriptors.isEmpty()) {
            return;
        }
        String placeholders = descriptors.stream()
                .map(item -> "?")
                .collect(Collectors.joining(","));
        jdbc.update("DELETE FROM agent_tool_definition"
                        + " WHERE tenant_id = '*' AND builtin = TRUE"
                        + " AND tool_code NOT IN (" + placeholders + ")",
                descriptors.stream().map(ToolDescriptor::code).toArray());
        // 绑定表跟着清，否则删掉的工具会在 agent_definition_tool 里留下孤儿行
        jdbc.update("DELETE FROM agent_definition_tool"
                + " WHERE tool_code NOT IN (SELECT tool_code FROM agent_tool_definition)");
    }

    /** 可见工具目录 = 全局内置 + 本租户自定义。 */
    public List<ToolCatalogRow> toolCatalog(String tenant) {
        return jdbc.query("""
                SELECT tool_code, name, description, tool_type, builtin, editable, pages,
                       permission_code, risk_level, min_approval, default_approval,
                       params_schema, config, group_name
                  FROM agent_tool_definition
                 WHERE tenant_id IN ('*', ?)
                 ORDER BY builtin DESC, tool_code
                """, this::mapToolRow, tenant);
    }

    /** 控制台目录：分组 + 关键词 + 分页。 */
    public List<ToolCatalogRow> searchToolCatalog(String tenant, String group,
                                                   String keyword, int offset, int size) {
        List<Object> args = toolArgs(tenant, group, keyword);
        args.add(size);
        args.add(offset);
        return jdbc.query(TOOL_SELECT + toolWhere(group, keyword)
                        + " ORDER BY builtin DESC, group_name NULLS LAST, tool_code LIMIT ? OFFSET ?",
                this::mapToolRow, args.toArray());
    }

    public long countToolCatalog(String tenant, String group, String keyword) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_tool_definition" + toolWhere(group, keyword),
                Long.class, toolArgs(tenant, group, keyword).toArray());
        return count == null ? 0 : count;
    }

    public List<String> toolGroups(String tenant) {
        return jdbc.queryForList("""
                SELECT DISTINCT group_name FROM agent_tool_definition
                 WHERE tenant_id IN ('*', ?) AND group_name IS NOT NULL AND group_name <> ''
                 ORDER BY group_name
                """, String.class, tenant);
    }

    private static final String TOOL_SELECT = """
            SELECT tool_code, name, description, tool_type, builtin, editable, pages,
                   permission_code, risk_level, min_approval, default_approval,
                   params_schema, config, group_name
              FROM agent_tool_definition
            """;

    private String toolWhere(String group, String keyword) {
        StringBuilder sql = new StringBuilder(" WHERE tenant_id IN ('*', ?)");
        if (group != null && !group.isBlank()) {
            sql.append(" AND group_name = ?");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (lower(tool_code) LIKE lower(?) OR lower(name) LIKE lower(?)"
                    + " OR lower(coalesce(description, '')) LIKE lower(?))");
        }
        return sql.toString();
    }

    private List<Object> toolArgs(String tenant, String group, String keyword) {
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (group != null && !group.isBlank()) {
            args.add(group.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return args;
    }

    public Optional<ToolCatalogRow> findTool(String tenant, String toolCode) {
        return jdbc.query("""
                SELECT tool_code, name, description, tool_type, builtin, editable, pages,
                       permission_code, risk_level, min_approval, default_approval,
                       params_schema, config, group_name
                  FROM agent_tool_definition
                 WHERE tenant_id IN ('*', ?) AND tool_code = ?
                 ORDER BY builtin DESC
                 LIMIT 1
                """, this::mapToolRow, tenant, toolCode).stream().findFirst();
    }

    public void insertCustomTool(String tenant, CustomToolInput input) {
        jdbc.update("""
                INSERT INTO agent_tool_definition
                    (tenant_id, tool_code, name, description, tool_type, builtin, editable,
                     pages, permission_code, risk_level, min_approval, default_approval,
                     params_schema, config, status, group_name)
                VALUES (?, ?, ?, ?, ?, FALSE, TRUE, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'ENABLED', ?)
                """, tenant, input.toolCode(), input.name(), input.description(),
                input.toolType().name(), writeJson(input.pages()), input.permissionCode(),
                input.riskLevel().name(), input.minApproval().name(),
                input.defaultApproval().name(), writeJson(input.paramsSchema()),
                writeJson(input.config()), input.groupName());
    }

    /** 只允许改自定义工具；内置工具在 SQL 层就被 editable=TRUE 拦住。 */
    public boolean updateCustomTool(String tenant, String toolCode, CustomToolInput input) {
        return jdbc.update("""
                UPDATE agent_tool_definition
                   SET name = ?, description = ?, tool_type = ?, pages = ?::jsonb,
                       permission_code = ?, risk_level = ?, min_approval = ?, default_approval = ?,
                       params_schema = ?::jsonb, config = ?::jsonb, group_name = ?, updated_at = now()
                 WHERE tenant_id = ? AND tool_code = ? AND editable = TRUE AND builtin = FALSE
                """, input.name(), input.description(), input.toolType().name(),
                writeJson(input.pages()), input.permissionCode(), input.riskLevel().name(),
                input.minApproval().name(), input.defaultApproval().name(),
                writeJson(input.paramsSchema()), writeJson(input.config()), input.groupName(),
                tenant, toolCode) > 0;
    }

    public boolean deleteCustomTool(String tenant, String toolCode) {
        boolean deleted = jdbc.update("""
                DELETE FROM agent_tool_definition
                 WHERE tenant_id = ? AND tool_code = ? AND editable = TRUE AND builtin = FALSE
                """, tenant, toolCode) > 0;
        if (deleted) {
            jdbc.update("DELETE FROM agent_definition_tool WHERE tenant_id = ? AND tool_code = ?",
                    tenant, toolCode);
        }
        return deleted;
    }

    private ToolCatalogRow mapToolRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new ToolCatalogRow(
                rs.getString("tool_code"),
                rs.getString("name"),
                rs.getString("description"),
                ToolType.valueOf(rs.getString("tool_type")),
                rs.getBoolean("builtin"),
                rs.getBoolean("editable"),
                readStringList(rs.getString("pages")).stream().collect(Collectors.toUnmodifiableSet()),
                rs.getString("permission_code"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                ApprovalMode.valueOf(rs.getString("min_approval")),
                ApprovalMode.valueOf(rs.getString("default_approval")),
                readMap(rs.getString("params_schema")),
                readMap(rs.getString("config")),
                rs.getString("group_name"));
    }

    private String writeJson(Object value) {
        return mapper.writeValueAsString(value == null ? Map.of() : value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(json, Map.class);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<?> raw = mapper.readValue(json, List.class);
        return raw.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}
