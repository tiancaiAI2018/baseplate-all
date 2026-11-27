package com.example.baseplate.platform.adapter.out.persistence;

import com.example.baseplate.platform.domain.ExternalEndpoint;
import com.example.baseplate.platform.domain.ExternalEndpointView;
import com.example.baseplate.platform.domain.ExternalService;
import com.example.baseplate.platform.domain.ExternalServiceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 外部服务目录持久化：显式 SQL + JSONB，风格与项目其它 starter 一致。
 *
 * <p>凭证只以密文进出；更新时传 null 表示保留旧值，避免编辑其它字段时误清空。
 * 带 View/Row 后缀的记录供控制台展示（含 status），映射成 domain record 的供注册表使用。</p>
 */
public final class ExternalServiceCatalogRepository {

    /** 与 Flyway 种子保持一致的默认租户：外部服务目录当前为单租户。 */
    public static final String DEFAULT_TENANT = "default";
    /** SQL 种子里的占位符，不是合法密文，启动时由 registry 容错置空。 */
    public static final String SEED_CREDENTIAL_PLACEHOLDER = "SQL_SEED_PLACEHOLDER";

    private static final String SERVICE_COLUMNS = """
            SELECT id, tenant_id, code, name, base_url, auth_type, credential_header,
                   credential_enc, static_headers::text AS static_headers, status
              FROM platform_external_service
            """;
    private static final String ENDPOINT_COLUMNS = """
            SELECT id, tenant_id, service_code, code, path, method, readonly, tool_code,
                   tool_name, tool_description, params_schema::text AS params_schema,
                   pages::text AS pages, permission_code, status, sort_no
              FROM platform_external_endpoint
            """;

    /** 服务写入入参；credentialEnc 为 null 表示更新时保留旧值。 */
    public record ServiceInput(String code, String name, String baseUrl, String authType,
                               String credentialHeader, String credentialEnc,
                               Map<String, String> staticHeaders, String status) {
    }

    /** 端点写入入参。 */
    public record EndpointInput(String code, String path, String method, boolean readonly,
                                String toolCode, String toolName, String toolDescription,
                                Map<String, Object> paramsSchema, List<String> pages,
                                String permissionCode, String status, int sortNo) {
    }

    /** 服务完整行（含状态与密文，仅 Repository 内部与控制台视图转换使用）。 */
    public record ServiceRow(long id, String tenantId, String code, String name, String baseUrl,
                             String authType, String credentialHeader, String credentialEnc,
                             Map<String, String> staticHeaders, String status) {
    }

    /** 端点完整行。 */
    public record EndpointRow(long id, String tenantId, String serviceCode, String code, String path,
                              String method, boolean readonly, String toolCode, String toolName,
                              String toolDescription, Map<String, Object> paramsSchema,
                              List<String> pages, String permissionCode, String status, int sortNo) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private final RowMapper<ServiceRow> SERVICE_MAPPER = (rs, i) -> new ServiceRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("code"),
            rs.getString("name"), rs.getString("base_url"), rs.getString("auth_type"),
            rs.getString("credential_header"), rs.getString("credential_enc"),
            readStringMap(rs.getString("static_headers")), rs.getString("status"));

    private final RowMapper<EndpointRow> ENDPOINT_MAPPER = (rs, i) -> new EndpointRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("service_code"),
            rs.getString("code"), rs.getString("path"), rs.getString("method"),
            rs.getBoolean("readonly"), rs.getString("tool_code"), rs.getString("tool_name"),
            rs.getString("tool_description"), readMap(rs.getString("params_schema")),
            readStringList(rs.getString("pages")), rs.getString("permission_code"),
            rs.getString("status"), rs.getInt("sort_no"));

    public ExternalServiceCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 控制台用：完整行（含停用项）
    // ------------------------------------------------------------------

    public List<ServiceRow> listServiceRows(String tenant) {
        return jdbc.query(SERVICE_COLUMNS + " WHERE tenant_id = ? ORDER BY code",
                SERVICE_MAPPER, tenant);
    }

    public Optional<ServiceRow> findServiceRow(String tenant, String code) {
        return jdbc.query(SERVICE_COLUMNS + " WHERE tenant_id = ? AND code = ?",
                SERVICE_MAPPER, tenant, code).stream().findFirst();
    }

    public List<EndpointRow> listEndpointRows(String tenant, String serviceCode) {
        return jdbc.query(ENDPOINT_COLUMNS + " WHERE tenant_id = ? AND service_code = ?"
                + " ORDER BY sort_no, code", ENDPOINT_MAPPER, tenant, serviceCode);
    }

    public Optional<EndpointRow> findEndpointRow(String tenant, String serviceCode, String code) {
        return jdbc.query(ENDPOINT_COLUMNS + " WHERE tenant_id = ? AND service_code = ? AND code = ?",
                ENDPOINT_MAPPER, tenant, serviceCode, code).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // 注册表用：只映射 ACTIVE 的服务与端点
    // ------------------------------------------------------------------

    public List<ExternalService> listServices(String tenant) {
        List<ExternalService> result = new ArrayList<>();
        for (ServiceRow row : listServiceRows(tenant)) {
            if (isActive(row.status())) {
                result.add(toService(row, activeEndpoints(tenant, row.code())));
            }
        }
        return result;
    }

    public Optional<ExternalService> findService(String tenant, String code) {
        return findServiceRow(tenant, code)
                .filter(row -> isActive(row.status()))
                .map(row -> toService(row, activeEndpoints(tenant, row.code())));
    }

    public List<ExternalEndpoint> listEndpoints(String tenant, String serviceCode) {
        return activeEndpoints(tenant, serviceCode);
    }

    public Optional<ExternalEndpoint> findEndpoint(String tenant, String serviceCode, String code) {
        return findEndpointRow(tenant, serviceCode, code)
                .filter(row -> isActive(row.status()))
                .map(ExternalServiceCatalogRepository::toEndpoint);
    }

    private List<ExternalEndpoint> activeEndpoints(String tenant, String serviceCode) {
        return listEndpointRows(tenant, serviceCode).stream()
                .filter(row -> isActive(row.status()))
                .map(ExternalServiceCatalogRepository::toEndpoint)
                .toList();
    }

    public ExternalService toService(ServiceRow row, List<ExternalEndpoint> endpoints) {
        return new ExternalService(row.code(), row.tenantId(), row.baseUrl(), row.authType(),
                row.credentialHeader(), row.credentialEnc(), endpoints, row.staticHeaders());
    }

    public static ExternalEndpoint toEndpoint(EndpointRow row) {
        return new ExternalEndpoint(row.code(), row.path(), row.method(), row.readonly(),
                row.toolCode(), row.toolName(), row.toolDescription(), row.paramsSchema(),
                new LinkedHashSet<>(row.pages()), row.permissionCode());
    }

    // ------------------------------------------------------------------
    // 控制台视图
    // ------------------------------------------------------------------

    public ExternalServiceView toView(ServiceRow row) {
        boolean configured = row.credentialEnc() != null && !row.credentialEnc().isBlank()
                && !SEED_CREDENTIAL_PLACEHOLDER.equals(row.credentialEnc());
        List<ExternalEndpointView> endpoints = listEndpointRows(row.tenantId(), row.code()).stream()
                .map(ExternalServiceCatalogRepository::toEndpointView)
                .toList();
        return new ExternalServiceView(row.code(), row.name(), row.baseUrl(), row.authType(),
                row.credentialHeader(), configured, row.status(), endpoints);
    }

    public static ExternalEndpointView toEndpointView(EndpointRow row) {
        return new ExternalEndpointView(row.code(), row.path(), row.method(), row.readonly(),
                row.toolCode(), row.toolName(), row.toolDescription(), row.paramsSchema(),
                row.pages(), row.permissionCode(), row.status(), row.sortNo());
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    public long insertService(String tenant, ServiceInput input) {
        Long id = jdbc.queryForObject("""
                INSERT INTO platform_external_service
                    (tenant_id, code, name, base_url, auth_type, credential_header,
                     credential_enc, static_headers, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id
                """, Long.class,
                tenant, input.code(), input.name(), input.baseUrl(), input.authType(),
                input.credentialHeader(), input.credentialEnc(),
                writeJson(input.staticHeaders()), status(input.status()));
        return id == null ? 0L : id;
    }

    public void updateService(String tenant, String code, ServiceInput input) {
        int rows = jdbc.update("""
                UPDATE platform_external_service
                   SET name = ?, base_url = ?, auth_type = ?, credential_header = ?,
                       credential_enc = COALESCE(?, credential_enc),
                       static_headers = COALESCE(?::jsonb, static_headers), status = ?, updated_at = now()
                 WHERE tenant_id = ? AND code = ?
                """,
                input.name(), input.baseUrl(), input.authType(), input.credentialHeader(),
                input.credentialEnc(), writeJson(input.staticHeaders()), status(input.status()),
                tenant, code);
        requireUpdated(rows, "外部服务", code);
    }

    public void deleteService(String tenant, String code) {
        int rows = jdbc.update("DELETE FROM platform_external_service WHERE tenant_id = ? AND code = ?",
                tenant, code);
        requireUpdated(rows, "外部服务", code);
    }

    public long insertEndpoint(String tenant, String serviceCode, EndpointInput input) {
        Long id = jdbc.queryForObject("""
                INSERT INTO platform_external_endpoint
                    (tenant_id, service_code, code, path, method, readonly, tool_code, tool_name,
                     tool_description, params_schema, pages, permission_code, status, sort_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class,
                tenant, serviceCode, input.code(), input.path(), input.method(),
                input.readonly(), input.toolCode(), input.toolName(), input.toolDescription(),
                writeJson(input.paramsSchema()), writeJson(input.pages()),
                input.permissionCode(), status(input.status()), input.sortNo());
        return id == null ? 0L : id;
    }

    public void updateEndpoint(String tenant, String serviceCode, String code, EndpointInput input) {
        int rows = jdbc.update("""
                UPDATE platform_external_endpoint
                   SET path = ?, method = ?, readonly = ?, tool_code = ?, tool_name = ?,
                       tool_description = ?, params_schema = ?::jsonb, pages = ?::jsonb,
                       permission_code = ?, status = ?, sort_no = ?, updated_at = now()
                 WHERE tenant_id = ? AND service_code = ? AND code = ?
                """,
                input.path(), input.method(), input.readonly(), input.toolCode(), input.toolName(),
                input.toolDescription(), writeJson(input.paramsSchema()), writeJson(input.pages()),
                input.permissionCode(), status(input.status()), input.sortNo(),
                tenant, serviceCode, code);
        requireUpdated(rows, "外部端点", code);
    }

    public void deleteEndpoint(String tenant, String serviceCode, String code) {
        int rows = jdbc.update("DELETE FROM platform_external_endpoint"
                + " WHERE tenant_id = ? AND service_code = ? AND code = ?", tenant, serviceCode, code);
        requireUpdated(rows, "外部端点", code);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String status(String value) {
        return value == null || value.isBlank() ? "ACTIVE" : value.toUpperCase(Locale.ROOT);
    }

    private static void requireUpdated(int rows, String label, String code) {
        if (rows == 0) {
            throw new IllegalArgumentException(label + "不存在：" + code);
        }
    }

    private static boolean isActive(String status) {
        return status == null || "ACTIVE".equalsIgnoreCase(status);
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

    private Map<String, String> readStringMap(String json) {
        Map<String, Object> raw = readMap(json);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<?> raw = mapper.readValue(json, List.class);
        return raw.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }
}
