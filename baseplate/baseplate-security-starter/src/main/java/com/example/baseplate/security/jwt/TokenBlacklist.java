package com.example.baseplate.security.jwt;

import com.example.baseplate.api.port.out.KeyValueStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/**
 * 令牌黑名单：只按 jti 存，TTL 与令牌剩余寿命一致，不无限增长。
 * 注销、改密、重置密码都必须拉黑，否则旧令牌仍能用到过期。
 */
public final class TokenBlacklist {

    private static final String PREFIX = "jwt:blacklist:";
    private static final String USER_REVOKED_PREFIX = "jwt:user-revoked:";
    private final KeyValueStore store;

    public TokenBlacklist(KeyValueStore store) {
        this.store = store;
    }

    public void revoke(JwtClaims claims) {
        long remaining = claims.expiresAt() - Instant.now().getEpochSecond();
        if (remaining > 0) {
            store.put(PREFIX + claims.jti(), "1", Duration.ofSeconds(remaining));
        }
    }

    /** 兼容按原始令牌注销（拿不到 claims 时的兜底路径）。 */
    public void revokeRaw(String token) {
        store.put(PREFIX + sha256(token), "1", Duration.ofHours(2));
    }

    public boolean isRevoked(JwtClaims claims) {
        if (claims.jti() != null && store.get(PREFIX + claims.jti()).isPresent()) {
            return true;
        }
        return isUserRevoked(claims.subject(), claims.issuedAt());
    }

    /**
     * 按用户整体吊销：改密、重置密码、禁用账号时必须调用。
     * 只靠单个 jti 黑名单无法覆盖「该用户其它设备上的令牌」。
     */
    public void revokeAllForUser(String userId) {
        store.put(USER_REVOKED_PREFIX + userId,
                String.valueOf(Instant.now().getEpochSecond()), Duration.ofDays(7));
    }

    /**
     * 必须严格大于：吊销时刻和 JWT 的 iat 都是秒级，管理员重置密码后用户在同一秒
     * 立刻登录时，新令牌的 iat 会等于吊销时刻。用 >= 会把刚签发的新令牌也拒掉，
     * 用户拿到一次性密码却永远改不了密（实测 401 令牌已注销）。
     */
    public boolean isUserRevoked(String userId, long issuedAt) {
        if (userId == null || issuedAt <= 0) {
            return false;
        }
        return store.get(USER_REVOKED_PREFIX + userId)
                .map(value -> {
                    try {
                        return Long.parseLong(value) > issuedAt;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    /** 用有效凭证登录成功后清除整体吊销标记：旧令牌已经在换凭证那一刻失效，不必再挂着一个全局开关。 */
    public void clearUserRevocation(String userId) {
        if (userId != null) {
            store.delete(USER_REVOKED_PREFIX + userId);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
