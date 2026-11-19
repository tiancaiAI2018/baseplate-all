package com.example.baseplate.metadata.application;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 低代码元数据：表单 / 列表 / 流程配置统一走同一套 Schema + UISchema。
 * 只存定义，不驱动运行时 DDL —— 物理表始终由 Flyway 独占。
 */
public final class MetadataService {

    private static final Set<String> KINDS = Set.of("FORM", "LIST", "FLOW");
    private static final Set<String> FIELD_TYPES = Set.of(
            "STRING", "TEXT", "NUMBER", "MONEY", "DATE", "DATETIME", "BOOLEAN",
            "SELECT", "MULTI_SELECT", "USER", "ORG", "FILE", "RICH_TEXT");
    private static final Set<String> FORBIDDEN = Set.of("script", "expression", "class", "eval");

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetadataService(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    public record SchemaView(long id, String kind, String code, String name, int version,
                             String status, boolean suspended,
                             String schemaJson, String uiSchemaJson) {
    }

    private static final RowMapper<SchemaView> MAPPER = (rs, i) -> new SchemaView(
            rs.getLong("id"), rs.getString("kind"), rs.getString("code"), rs.getString("name"),
            rs.getInt("version"), rs.getString("status"), rs.getBoolean("suspended"),
            rs.getString("schema_json"), rs.getString("ui_schema_json"));

    public List<SchemaView> list(String kind) {
        return list(currentUser.current().tenantId(), kind);
    }

    /**
     * 显式传租户的查询入口。
     *
     * <p>Agent 工具、定时任务等运行在异步线程，没有 SecurityContext，
     * 走 {@link CurrentUserProvider} 会直接抛「未认证」。调用方已有租户上下文时用这个重载。</p>
     */
    public List<SchemaView> list(String tenant, String kind) {
        if (kind != null && !kind.isBlank()) {
            return jdbc.query("SELECT DISTINCT ON (code) * FROM meta_schema"
                            + " WHERE tenant_id = ? AND kind = ? ORDER BY code, version DESC",
                    MAPPER, tenant, kind.toUpperCase());
        }
        return jdbc.query("SELECT DISTINCT ON (kind, code) * FROM meta_schema"
                + " WHERE tenant_id = ? ORDER BY kind, code, version DESC", MAPPER, tenant);
    }

    public SchemaView latest(String kind, String code) {
        return latest(currentUser.current().tenantId(), kind, code);
    }

    /** 显式传租户的读取入口，理由同 {@link #list(String, String)}。 */
    public SchemaView latest(String tenant, String kind, String code) {
        List<SchemaView> rows = jdbc.query("SELECT * FROM meta_schema WHERE tenant_id = ?"
                        + " AND kind = ? AND code = ? ORDER BY version DESC LIMIT 1",
                MAPPER, tenant, normalizeKind(kind), code);
        return rows.stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("元数据不存在：" + kind + "/" + code));
    }

    public SchemaView saveDraft(String kind, String code, String name,
                                String schemaJson, String uiSchemaJson) {
        String tenant = currentUser.current().tenantId();
        String normalizedKind = normalizeKind(kind);
        validateSchema(schemaJson);
        Integer next = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM meta_schema"
                        + " WHERE tenant_id = ? AND kind = ? AND code = ?",
                Integer.class, tenant, normalizedKind, code);
        int version = next == null ? 1 : next;
        jdbc.update("INSERT INTO meta_schema(tenant_id, kind, code, name, version, status,"
                        + " schema_json, ui_schema_json) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, ?::jsonb)",
                tenant, normalizedKind, code, name == null ? code : name, version,
                schemaJson, uiSchemaJson == null ? "{}" : uiSchemaJson);
        return latest(normalizedKind, code);
    }

    public SchemaView publish(String kind, String code, int version) {
        String tenant = currentUser.current().tenantId();
        String normalizedKind = normalizeKind(kind);
        int updated = jdbc.update("UPDATE meta_schema SET status = 'PUBLISHED', published_at = now()"
                + " WHERE tenant_id = ? AND kind = ? AND code = ? AND version = ?",
                tenant, normalizedKind, code, version);
        if (updated == 0) {
            throw new IllegalArgumentException("指定版本不存在");
        }
        jdbc.update("UPDATE meta_schema SET status = 'ARCHIVED'"
                + " WHERE tenant_id = ? AND kind = ? AND code = ? AND version <> ? AND status = 'PUBLISHED'",
                tenant, normalizedKind, code, version);
        return latest(normalizedKind, code);
    }

    /**
     * 作废/恢复整个定义（按 code 全版本置标记）。
     * 与工作流定义一致：只挡新绑定，已经绑定该表单的节点仍能按原样渲染。
     */
    public SchemaView suspend(String kind, String code, boolean suspended) {
        String tenant = currentUser.current().tenantId();
        String normalizedKind = normalizeKind(kind);
        int updated = jdbc.update("UPDATE meta_schema SET suspended = ?"
                        + " WHERE tenant_id = ? AND kind = ? AND code = ?",
                suspended, tenant, normalizedKind, code);
        if (updated == 0) {
            throw new IllegalArgumentException("元数据不存在：" + kind + "/" + code);
        }
        return latest(normalizedKind, code);
    }

    @SuppressWarnings("unchecked")
    public void validateSchema(String schemaJson) {
        Map<String, Object> schema;
        try {
            schema = objectMapper.readValue(schemaJson, Map.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Schema 不是合法 JSON");
        }
        Object fields = schema.get("fields");
        if (!(fields instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Schema 必须包含非空 fields 数组");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> field)) {
                throw new IllegalArgumentException("fields 元素必须是对象");
            }
            Object name = field.get("name");
            Object type = field.get("type");
            if (name == null || String.valueOf(name).isBlank()) {
                throw new IllegalArgumentException("字段缺少 name");
            }
            if (type == null || !FIELD_TYPES.contains(String.valueOf(type))) {
                throw new IllegalArgumentException("字段 " + name + " 的 type 不在白名单内：" + type);
            }
            String normalized = String.valueOf(name).toLowerCase();
            // 按分隔符拆词后再比对保留词：description 是正常字段名，
            // 用 contains 会把 de-script-ion 误判成危险命名，直接把合法表单拒掉。
            for (String token : normalized.split("[^a-z0-9]+")) {
                if (FORBIDDEN.contains(token)) {
                    throw new IllegalArgumentException("字段名不允许包含保留词：" + token);
                }
            }
        }
    }

    public Map<String, Object> render(String kind, String code) {
        SchemaView view = latest(kind, code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", view.kind());
        result.put("code", view.code());
        result.put("version", view.version());
        result.put("schema", objectMapper.readValue(view.schemaJson(), Map.class));
        result.put("uiSchema", objectMapper.readValue(view.uiSchemaJson(), Map.class));
        return result;
    }

    private String normalizeKind(String kind) {
        String normalized = kind == null ? "" : kind.toUpperCase();
        if (!KINDS.contains(normalized)) {
            throw new IllegalArgumentException("kind 必须是 FORM / LIST / FLOW");
        }
        return normalized;
    }
}
