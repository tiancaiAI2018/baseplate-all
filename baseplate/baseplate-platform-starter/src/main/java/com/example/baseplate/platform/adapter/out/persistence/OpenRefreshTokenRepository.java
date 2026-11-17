package com.example.baseplate.platform.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 刷新令牌：只存 SHA-256，即使库被拖走也换不出新令牌。
 *
 * <p>换新时做轮换（rotation）：旧令牌立刻置 {@code revoked_at}，新的重新下发。
 * 这样一张泄漏的 refresh token 只能用一次，而且一旦被第三方先用掉，
 * 真正的接入方下次续期会失败，异常就被暴露出来了。</p>
 */
public final class OpenRefreshTokenRepository {

    public record RefreshRow(String tokenHash, long appId, String tenantId, String userId,
                             String username, String scopes, OffsetDateTime expiresAt) {
    }

    private static final RowMapper<RefreshRow> MAPPER = (rs, i) -> new RefreshRow(
            rs.getString("token_hash"), rs.getLong("app_id"), rs.getString("tenant_id"),
            rs.getString("user_id"), rs.getString("username"), rs.getString("scopes"),
            rs.getObject("expires_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public OpenRefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void issue(String tokenHash, long appId, String tenantId, String userId,
                      String username, String scopes, Duration ttl) {
        jdbc.update("INSERT INTO open_refresh_token(token_hash, app_id, tenant_id, user_id,"
                        + " username, scopes, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, now() + make_interval(secs => ?))",
                tokenHash, appId, tenantId, userId, username, scopes, (double) ttl.toSeconds());
    }

    /** 原子消费：返回空表示「不存在 / 已轮换 / 已过期」。 */
    public Optional<RefreshRow> consume(String tokenHash) {
        List<RefreshRow> rows = jdbc.query("UPDATE open_refresh_token SET revoked_at = now()"
                        + " WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()"
                        + " RETURNING *", MAPPER, tokenHash);
        return rows.stream().findFirst();
    }

    /** 撤销某用户在某应用上的全部刷新令牌：用户被停用、授权被回收时调用。 */
    public int revokeAll(long appId, String userId) {
        return jdbc.update("UPDATE open_refresh_token SET revoked_at = now()"
                + " WHERE app_id = ? AND user_id = ? AND revoked_at IS NULL", appId, userId);
    }

    /** 应用被停用、关闭授权码能力或轮换密钥时，整应用的用户授权一起作废。 */
    public int revokeAllByApp(long appId) {
        return jdbc.update("UPDATE open_refresh_token SET revoked_at = now()"
                + " WHERE app_id = ? AND revoked_at IS NULL", appId);
    }

    /** 授权页用来判断「这个用户之前是否授权过这个应用」。 */
    public Optional<RefreshRow> latestActive(long appId, String userId) {
        List<RefreshRow> rows = jdbc.query("SELECT * FROM open_refresh_token"
                        + " WHERE app_id = ? AND user_id = ? AND revoked_at IS NULL"
                        + " AND expires_at > now() ORDER BY created_at DESC LIMIT 1",
                MAPPER, appId, userId);
        return rows.stream().findFirst();
    }

    public int purge() {
        return jdbc.update("DELETE FROM open_refresh_token WHERE expires_at < now() - interval '30 days'");
    }
}
