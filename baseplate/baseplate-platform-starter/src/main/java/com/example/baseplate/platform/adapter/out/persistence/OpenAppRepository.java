package com.example.baseplate.platform.adapter.out.persistence;

import com.example.baseplate.platform.domain.OpenApp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 开放平台持久化：显式 SQL，密钥字段只存密文。 */
public final class OpenAppRepository {

    private final JdbcTemplate jdbc;

    public OpenAppRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SecretRow(long id, String tenantId, String appKey, String appName,
                            String clientSecretEnc, String signingSecretEnc, String authMode,
                            String grantTypes, String scopes, String ipWhitelist, int qpsLimit,
                            int dayLimit, String status, boolean sandboxEnabled,
                            String redirectUris) {

        public boolean supportsGrant(String grantType) {
            return grantTypes != null && List.of(grantTypes.split(",")).contains(grantType);
        }

        /** 回调白名单：换行或逗号分隔，必须精确匹配（不做前缀通配，防开放重定向）。 */
        public List<String> redirectUriList() {
            if (redirectUris == null || redirectUris.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(redirectUris.split("[\\r\\n,]+"))
                    .map(String::trim).filter(item -> !item.isEmpty()).toList();
        }
    }

    /** 新建应用的入参：参数太多，用记录承载，避免位置错乱。 */
    public record NewApp(String tenantId, String appKey, String appName, String clientSecretEnc,
                         String signingSecretEnc, String authMode, String grantTypes,
                         String scopes, String ipWhitelist, int qpsLimit, int dayLimit,
                         boolean sandbox, String redirectUris) {
    }

    /** 可编辑字段：安全相关（密钥）不在其中，只能走轮换。 */
    public record AppUpdate(String appName, String authMode, String grantTypes, String scopes,
                            String ipWhitelist, int qpsLimit, int dayLimit, String status,
                            boolean sandbox, String redirectUris) {
    }

    private static final RowMapper<OpenApp> APP_MAPPER = (rs, i) -> new OpenApp(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("app_key"),
            rs.getString("app_name"), rs.getString("auth_mode"), rs.getString("grant_types"),
            rs.getString("scopes"), rs.getString("ip_whitelist"), rs.getInt("qps_limit"),
            rs.getInt("day_limit"), rs.getString("status"), rs.getBoolean("sandbox_enabled"),
            rs.getString("redirect_uris"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<SecretRow> SECRET_MAPPER = (rs, i) -> new SecretRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("app_key"),
            rs.getString("app_name"), rs.getString("client_secret_enc"),
            rs.getString("signing_secret_enc"), rs.getString("auth_mode"),
            rs.getString("grant_types"), rs.getString("scopes"), rs.getString("ip_whitelist"),
            rs.getInt("qps_limit"), rs.getInt("day_limit"), rs.getString("status"),
            rs.getBoolean("sandbox_enabled"), rs.getString("redirect_uris"));

    public List<OpenApp> list(String tenant) {
        return jdbc.query("SELECT * FROM open_app WHERE tenant_id = ? AND status <> 'DELETED'"
                        + " ORDER BY id DESC",
                APP_MAPPER, tenant);
    }

    public Optional<OpenApp> find(String tenant, long id) {
        return jdbc.query("SELECT * FROM open_app WHERE tenant_id = ? AND id = ?",
                APP_MAPPER, tenant, id).stream().findFirst();
    }

    public Optional<SecretRow> findSecrets(long id) {
        return jdbc.query("SELECT * FROM open_app WHERE id = ?", SECRET_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<SecretRow> findByAppKey(String appKey) {
        return jdbc.query("SELECT * FROM open_app WHERE app_key = ?", SECRET_MAPPER, appKey)
                .stream().findFirst();
    }

    public long insert(NewApp app) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO open_app(tenant_id, app_key, app_name, client_secret_enc,"
                            + " signing_secret_enc, auth_mode, grant_types, scopes, ip_whitelist,"
                            + " qps_limit, day_limit, status, sandbox_enabled, redirect_uris)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    new String[]{"id"});
            ps.setString(1, app.tenantId());
            ps.setString(2, app.appKey());
            ps.setString(3, app.appName());
            ps.setString(4, app.clientSecretEnc());
            ps.setString(5, app.signingSecretEnc());
            ps.setString(6, app.authMode());
            ps.setString(7, app.grantTypes());
            ps.setString(8, app.scopes());
            ps.setString(9, app.ipWhitelist());
            ps.setInt(10, app.qpsLimit());
            ps.setInt(11, app.dayLimit());
            ps.setBoolean(12, app.sandbox());
            ps.setString(13, app.redirectUris());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void update(long id, AppUpdate app) {
        jdbc.update("UPDATE open_app SET app_name = ?, auth_mode = ?, grant_types = ?, scopes = ?,"
                        + " ip_whitelist = ?, qps_limit = ?, day_limit = ?, status = ?,"
                        + " sandbox_enabled = ?, redirect_uris = ? WHERE id = ?",
                app.appName(), app.authMode(), app.grantTypes(), app.scopes(),
                app.ipWhitelist(), app.qpsLimit(), app.dayLimit(), app.status(),
                app.sandbox(), app.redirectUris(), id);
    }

    /** 软删除，供控制台清理应用；DELETED 的应用不再出现在列表里，鉴权也会判定为停用。 */
    public void markDeleted(String tenant, long id) {
        jdbc.update("UPDATE open_app SET status = 'DELETED' WHERE tenant_id = ? AND id = ?", tenant, id);
    }

    public void rotateSecrets(long id, String clientSecretEnc, String signingSecretEnc) {
        jdbc.update("UPDATE open_app SET client_secret_enc = ?, signing_secret_enc = ?,"
                + " rotated_at = now() WHERE id = ?", clientSecretEnc, signingSecretEnc, id);
    }

    public void log(String tenant, String appKey, String appName, boolean sandbox, String traceId,
                    String method, String path, int status, String resultCode, long latencyMs,
                    String clientIp, String apiCode, String userAgent, String actUser) {
        jdbc.update("INSERT INTO open_api_log(tenant_id, app_key, app_name, sandbox, trace_id, method,"
                        + " path, status, result_code, latency_ms, client_ip, api_code, user_agent,"
                        + " act_user) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenant, appKey, appName, sandbox, traceId, method, path, status, resultCode,
                (int) latencyMs, clientIp, apiCode,
                userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 500)),
                actUser);
    }

    public List<Map<String, Object>> logs(String tenant, String appKey, int limit) {
        return jdbc.queryForList("SELECT id, app_key, app_name, sandbox, trace_id, method, path,"
                + " status, result_code, latency_ms, client_ip, api_code, user_agent, act_user,"
                + " created_at FROM open_api_log WHERE tenant_id = ? AND app_key = ?"
                + " ORDER BY id DESC LIMIT ?", tenant, appKey, limit);
    }

    public Map<String, Object> logDetail(String tenant, String appKey, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM open_api_log WHERE tenant_id = ? AND app_key = ? AND id = ?",
                tenant, appKey, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> usage(String tenant, String appKey, int days) {
        return jdbc.queryForList("SELECT to_char(created_at, 'YYYY-MM-DD') AS day, count(*) AS calls,"
                + " sum(CASE WHEN status >= 400 THEN 1 ELSE 0 END) AS errors"
                + " FROM open_api_log WHERE tenant_id = ? AND app_key = ?"
                + " AND created_at >= now() - (? || ' days')::interval"
                + " GROUP BY 1 ORDER BY 1", tenant, appKey, String.valueOf(days));
    }
}
