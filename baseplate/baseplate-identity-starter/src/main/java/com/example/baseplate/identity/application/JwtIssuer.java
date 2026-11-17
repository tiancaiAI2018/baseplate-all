package com.example.baseplate.identity.application;

import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 自研 HMAC-SHA256 JWT 签发：零第三方 JWT 依赖，签名逻辑可控。
 * 令牌内嵌角色、权限点与密码状态，入站不再每次请求查库。
 */
public final class JwtIssuer {

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtIssuer(String secret, Duration ttl) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    public String issue(String userId, String tenant, String username,
                        Set<String> roles, Set<String> permissions, boolean mustChangePassword) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("sub", userId);
            claims.put("tenant", tenant);
            claims.put("username", username);
            claims.put("roles", roles == null ? List.of() : List.copyOf(roles));
            claims.put("permissions", permissions == null ? List.of() : List.copyOf(permissions));
            claims.put("mustChange", mustChangePassword);
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", Instant.now().plus(ttl).getEpochSecond());
            String payload = base64Url(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return signingInput + "." + base64Url(hmac(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    private byte[] hmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
