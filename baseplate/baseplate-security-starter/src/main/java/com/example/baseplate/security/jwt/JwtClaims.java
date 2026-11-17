package com.example.baseplate.security.jwt;

import java.util.Set;

/** 校验通过的令牌声明：只保留入站需要的字段。 */
public record JwtClaims(
        String jti,
        String subject,
        String tenant,
        String username,
        Set<String> roles,
        Set<String> permissions,
        boolean mustChangePassword,
        long issuedAt,
        long expiresAt) {
}
