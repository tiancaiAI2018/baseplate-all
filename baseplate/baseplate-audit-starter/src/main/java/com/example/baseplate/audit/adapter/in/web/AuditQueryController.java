package com.example.baseplate.audit.adapter.in.web;

import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 审计查询：列表带筛选，详情按 id 返回完整 JSON。 */
@RestController
@RequestMapping("/api/audit")
public class AuditQueryController {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;

    public AuditQueryController(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping("/access")
    @RequiresPermission("audit:view")
    public Map<String, Object> access(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String actor,
                                      @RequestParam(required = false) String path,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String source) {
        String tenant = currentUser.current().tenantId();
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (hasText(actor)) {
            where.append(" AND actor ILIKE ?");
            args.add("%" + actor.trim() + "%");
        }
        if (hasText(path)) {
            where.append(" AND resource ILIKE ?");
            args.add("%" + path.trim() + "%");
        }
        if (hasText(status)) {
            where.append(" AND result_code = ?");
            args.add(status.trim());
        }
        if ("THIRD_PARTY".equalsIgnoreCase(source)) {
            where.append(" AND resource LIKE '/openapi/%'");
        } else if ("INTERNAL".equalsIgnoreCase(source)) {
            where.append(" AND resource NOT LIKE '/openapi/%'");
        }
        int limit = Math.min(200, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * limit;
        // source / app_key / api_code 直接从 detail 里取，避免为了两个展示字段再加表列
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, trace_id, actor, action, resource, result_code, client_ip, user_agent,"
                        + " duration_ms, request_query, created_at,"
                        + " CASE WHEN resource LIKE '/openapi/%' THEN 'THIRD_PARTY' ELSE 'INTERNAL' END AS source,"
                        + " detail->>'appKey' AS app_key, detail->>'appName' AS app_name,"
                        + " detail->>'apiCode' AS api_code"
                        + " FROM aud_access_log"
                        + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                append(args, limit, offset).toArray());
        Long total = jdbc.queryForObject("SELECT count(*) FROM aud_access_log" + where,
                Long.class, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    @GetMapping("/access/{id}")
    @RequiresPermission("audit:view")
    public Map<String, Object> accessDetail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, trace_id, actor, action, resource, result_code, client_ip,"
                        + " user_agent, duration_ms, request_query, detail::text AS detail, created_at,"
                        + " CASE WHEN resource LIKE '/openapi/%' THEN 'THIRD_PARTY' ELSE 'INTERNAL' END AS source,"
                        + " detail->>'appKey' AS app_key, detail->>'appName' AS app_name,"
                        + " detail->>'apiCode' AS api_code, detail->>'sandbox' AS sandbox"
                        + " FROM aud_access_log WHERE tenant_id = ? AND id = ?",
                currentUser.current().tenantId(), id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("审计记录不存在");
        }
        return rows.get(0);
    }

    @GetMapping("/business")
    @RequiresPermission("audit:view")
    public Map<String, Object> business(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String actor,
                                        @RequestParam(required = false) String action) {
        String tenant = currentUser.current().tenantId();
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (hasText(actor)) {
            where.append(" AND actor ILIKE ?");
            args.add("%" + actor.trim() + "%");
        }
        if (hasText(action)) {
            where.append(" AND action ILIKE ?");
            args.add("%" + action.trim() + "%");
        }
        int limit = Math.min(200, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * limit;
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, trace_id, actor, action, resource, created_at FROM aud_business_log"
                        + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                append(args, limit, offset).toArray());
        Long total = jdbc.queryForObject("SELECT count(*) FROM aud_business_log" + where,
                Long.class, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    @GetMapping("/business/{id}")
    @RequiresPermission("audit:view")
    public Map<String, Object> businessDetail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, trace_id, actor, action, resource,"
                        + " before_json::text AS before_json, after_json::text AS after_json, created_at"
                        + " FROM aud_business_log WHERE tenant_id = ? AND id = ?",
                currentUser.current().tenantId(), id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("审计记录不存在");
        }
        return rows.get(0);
    }

    @GetMapping("/logins")
    @RequiresPermission("audit:view")
    public Map<String, Object> logins(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) String username,
                                      @RequestParam(required = false) Boolean success) {
        CurrentUser user = currentUser.current();
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(user.tenantId());
        if (hasText(username)) {
            where.append(" AND username ILIKE ?");
            args.add("%" + username.trim() + "%");
        }
        if (success != null) {
            where.append(" AND success = ?");
            args.add(success);
        }
        int limit = Math.min(200, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * limit;
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, username, success, client_ip, reason, created_at FROM sys_login_log"
                        + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                append(args, limit, offset).toArray());
        Long total = jdbc.queryForObject("SELECT count(*) FROM sys_login_log" + where,
                Long.class, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static List<Object> append(List<Object> args, int limit, int offset) {
        List<Object> all = new ArrayList<>(args);
        all.add(limit);
        all.add(offset);
        return all;
    }
}
