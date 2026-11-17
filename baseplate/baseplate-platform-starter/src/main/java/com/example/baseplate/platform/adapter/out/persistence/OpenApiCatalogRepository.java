package com.example.baseplate.platform.adapter.out.persistence;

import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.platform.domain.OpenApiDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * API 目录与应用授权。
 *
 * <p>目录的事实来源是代码里的 {@code @OpenApi} 注解：启动时 upsert 进库，库里只保留
 * 运营侧的两类改动——改名（{@code label_edited}）与停用（{@code status}）。
 * 方法、路径、主体类型、所需权限这些安全边界一律以注解为准，页面改不动。</p>
 *
 * <p>控制台还可以手工登记（{@code source=MANUAL}），用于接口不在本进程内的场景；
 * 一旦某个 code 被注解声明，手工条目会被覆盖并在日志里告警——代码优先。</p>
 */
public final class OpenApiCatalogRepository {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiCatalogRepository.class);

    private static final String UPSERT = "INSERT INTO open_api(code, name, method, path,"
            + " group_name, description, subject, required_permission, required_scope,"
            + " source, handler, sandbox_allowed)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (code) DO UPDATE SET"
            // 人工改过名的条目，同步时只更新安全字段，不动展示文案
            + " name = CASE WHEN open_api.label_edited THEN open_api.name ELSE EXCLUDED.name END,"
            + " group_name = CASE WHEN open_api.label_edited THEN open_api.group_name ELSE EXCLUDED.group_name END,"
            + " description = CASE WHEN open_api.label_edited THEN open_api.description ELSE EXCLUDED.description END,"
            + " method = EXCLUDED.method,"
            + " path = EXCLUDED.path,"
            + " subject = EXCLUDED.subject,"
            + " required_permission = EXCLUDED.required_permission,"
            + " required_scope = EXCLUDED.required_scope,"
            + " source = EXCLUDED.source,"
            + " handler = EXCLUDED.handler,"
            + " sandbox_allowed = EXCLUDED.sandbox_allowed,"
            + " updated_at = now()";

    private static final RowMapper<OpenApiDef> DEF_MAPPER = (rs, i) -> new OpenApiDef(
            rs.getString("code"), rs.getString("name"), rs.getString("method"),
            rs.getString("path"), rs.getString("group_name"), rs.getString("description"),
            parseSubject(rs.getString("subject")),
            rs.getString("required_permission"), rs.getString("required_scope"),
            rs.getString("source"), rs.getString("handler"),
            rs.getBoolean("sandbox_allowed"), rs.getBoolean("label_edited"),
            "ACTIVE".equals(rs.getString("status")));

    private final JdbcTemplate jdbc;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private volatile List<OpenApiDef> cache = List.of();

    public OpenApiCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动期把注解扫描结果同步进库；重复启动幂等。 */
    public void ensureCatalog(List<OpenApiDef> scanned) {
        for (OpenApiDef def : scanned) {
            List<String> sources = jdbc.queryForList(
                    "SELECT source FROM open_api WHERE code = ?", String.class, def.code());
            if (!sources.isEmpty() && "MANUAL".equals(sources.get(0))) {
                LOG.warn("开放接口 {} 原为控制台手工登记，现被注解声明覆盖（代码优先）", def.code());
            }
        }
        for (OpenApiDef def : scanned) {
            jdbc.update(UPSERT, def.code(), def.name(), def.method(), def.path(),
                    def.groupName(), def.description(), def.subject().name(),
                    def.requiredPermission(), def.requiredScope(), "ANNOTATION",
                    def.handler(), def.sandboxAllowed());
        }
        refresh();
        LOG.info("开放接口目录已同步：{} 条（注解 {} 条，手工 {} 条）",
                cache.size(), cache.stream().filter(d -> "ANNOTATION".equals(d.source())).count(),
                cache.stream().filter(d -> "MANUAL".equals(d.source())).count());
    }

    public void refresh() {
        cache = jdbc.query("SELECT * FROM open_api WHERE status = 'ACTIVE' ORDER BY group_name, code",
                DEF_MAPPER);
    }

    /** 鉴权链用的目录：只含启用中的条目。 */
    public List<OpenApiDef> catalog() {
        if (cache.isEmpty()) {
            refresh();
        }
        return cache;
    }

    /** 控制台用的全量目录：包含已停用，便于重新启用。 */
    public List<OpenApiDef> all() {
        return jdbc.query("SELECT * FROM open_api ORDER BY status, group_name, code", DEF_MAPPER);
    }

    public Optional<OpenApiDef> find(String code) {
        return jdbc.query("SELECT * FROM open_api WHERE code = ?", DEF_MAPPER, code)
                .stream().findFirst();
    }

    /** 手工登记：只用于接口不在本进程内的情况，不与注解抢同一个 code。 */
    public void manualRegister(OpenApiDef def) {
        if (!def.path().startsWith("/openapi/")) {
            throw new IllegalArgumentException("手工登记的接口路径必须以 /openapi/ 开头");
        }
        List<String> existing = jdbc.queryForList(
                "SELECT source FROM open_api WHERE code = ?", String.class, def.code());
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("接口编码已存在：" + def.code()
                    + ("ANNOTATION".equals(existing.get(0)) ? "（由代码注解声明，不能手工覆盖）" : ""));
        }
        jdbc.update("INSERT INTO open_api(code, name, method, path, group_name, description,"
                        + " subject, required_permission, required_scope, source, handler,"
                        + " sandbox_allowed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?)",
                def.code(), def.name(), def.method(), def.path(), def.groupName(),
                def.description(), def.subject().name(), def.requiredPermission(),
                def.requiredScope(), def.handler(), def.sandboxAllowed());
        refresh();
    }

    /** 只改展示文案：安全字段（method/path/subject/permission）不接受页面修改。 */
    public void relabel(String code, String name, String groupName, String description) {
        int rows = jdbc.update("UPDATE open_api SET name = ?, group_name = ?, description = ?,"
                        + " label_edited = true, updated_at = now() WHERE code = ?",
                name, groupName, description, code);
        if (rows == 0) {
            throw new IllegalArgumentException("接口不存在：" + code);
        }
        refresh();
    }

    public void setEnabled(String code, boolean enabled) {
        int rows = jdbc.update("UPDATE open_api SET status = ?, updated_at = now() WHERE code = ?",
                enabled ? "ACTIVE" : "DISABLED", code);
        if (rows == 0) {
            throw new IllegalArgumentException("接口不存在：" + code);
        }
        refresh();
    }

    /** 删除只对手工登记开放：注解声明的条目下次启动会回来，删了等于自欺欺人。 */
    public void deleteManual(String code) {
        OpenApiDef def = find(code).orElseThrow(() -> new IllegalArgumentException("接口不存在：" + code));
        if (!"MANUAL".equals(def.source())) {
            throw new IllegalArgumentException("注解声明的接口不能删除，请改为停用");
        }
        jdbc.update("DELETE FROM open_app_api WHERE api_code = ?", code);
        jdbc.update("DELETE FROM open_api WHERE code = ?", code);
        refresh();
    }

    /**
     * 新应用默认开通的接口：内置基础能力（code 以 openapi. 开头）。
     * 业务接口一律要显式勾选，避免新建应用就拿到一堆不该有的权限。
     */
    public List<String> defaultClientCodes() {
        return catalog().stream()
                .filter(OpenApiDef::clientAllowed)
                .map(OpenApiDef::code)
                .filter(code -> code.startsWith("openapi."))
                .toList();
    }

    public List<String> grantedCodes(long appId) {
        return jdbc.queryForList("SELECT api_code FROM open_app_api WHERE app_id = ? ORDER BY api_code",
                String.class, appId);
    }

    public int countGrants(long appId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM open_app_api WHERE app_id = ?",
                Long.class, appId);
        return count == null ? 0 : count.intValue();
    }

    public void replaceGrants(long appId, List<String> codes) {
        Set<String> valid = new LinkedHashSet<>();
        for (OpenApiDef def : catalog()) {
            valid.add(def.code());
        }
        List<String> filtered = codes == null ? List.of()
                : codes.stream().filter(valid::contains).distinct().toList();
        jdbc.update("DELETE FROM open_app_api WHERE app_id = ?", appId);
        for (String code : filtered) {
            jdbc.update("INSERT INTO open_app_api(app_id, api_code) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", appId, code);
        }
    }

    public void grant(long appId, List<String> codes) {
        for (String code : codes) {
            jdbc.update("INSERT INTO open_app_api(app_id, api_code) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", appId, code);
        }
    }

    public boolean isGranted(long appId, String code) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM open_app_api WHERE app_id = ? AND api_code = ?",
                Long.class, appId, code);
        return count != null && count > 0;
    }

    public Optional<OpenApiDef> match(String method, String path) {
        for (OpenApiDef def : catalog()) {
            if (def.method().equalsIgnoreCase(method) && matcher.match(def.path(), path)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    public List<String> groups() {
        List<String> groups = new ArrayList<>();
        for (OpenApiDef def : catalog()) {
            String group = def.groupName() == null ? "未分组" : def.groupName();
            if (!groups.contains(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    private static OpenApiSubject parseSubject(String value) {
        if (value == null || value.isBlank()) {
            return OpenApiSubject.CLIENT;
        }
        try {
            return OpenApiSubject.valueOf(value);
        } catch (IllegalArgumentException e) {
            return OpenApiSubject.CLIENT;
        }
    }
}
