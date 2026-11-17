package com.example.baseplate.platform.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 授权码：用户点「同意」后发出的一次性凭证，第三方后端拿它换令牌。
 *
 * <p>三个约束写在一次 UPDATE 里，靠数据库保证原子性：</p>
 * <ol>
 *   <li>只能消费一次（{@code consumed_at IS NULL}）；</li>
 *   <li>没过期（{@code expires_at > now()}）；</li>
 *   <li>换码时的 {@code redirect_uri} 必须与发码时一致（防中途被换掉）。</li>
 * </ol>
 * <p>先查后改会留出并发窗口，两个请求同时拿同一个 code 都能换到令牌。</p>
 */
public final class OpenAuthCodeRepository {

    public record AuthCode(String code, long appId, String tenantId, String userId,
                           String username, String scopes, String redirectUri,
                           OffsetDateTime expiresAt, OffsetDateTime consumedAt) {
    }

    private static final RowMapper<AuthCode> MAPPER = (rs, i) -> new AuthCode(
            rs.getString("code"), rs.getLong("app_id"), rs.getString("tenant_id"),
            rs.getString("user_id"), rs.getString("username"), rs.getString("scopes"),
            rs.getString("redirect_uri"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("consumed_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public OpenAuthCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void issue(String code, long appId, String tenantId, String userId, String username,
                      String scopes, String redirectUri, Duration ttl) {
        jdbc.update("INSERT INTO open_auth_code(code, app_id, tenant_id, user_id, username,"
                        + " scopes, redirect_uri, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, now() + make_interval(secs => ?))",
                code, appId, tenantId, userId, username, scopes, redirectUri,
                (double) ttl.toSeconds());
    }

    /** 原子消费：返回空表示「不存在 / 已用过 / 已过期 / redirect_uri 不符」。 */
    public Optional<AuthCode> consume(String code, long appId, String redirectUri) {
        List<AuthCode> rows = jdbc.query("UPDATE open_auth_code SET consumed_at = now()"
                        + " WHERE code = ? AND app_id = ? AND redirect_uri = ?"
                        + " AND consumed_at IS NULL AND expires_at > now() RETURNING *",
                MAPPER, code, appId, redirectUri);
        return rows.stream().findFirst();
    }

    /** 清理过期与已消费的码；由启动任务调用一次即可，量很小。 */
    public int purge() {
        return jdbc.update("DELETE FROM open_auth_code WHERE expires_at < now() - interval '1 day'");
    }
}
