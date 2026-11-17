package com.example.baseplate.platform.token;

import com.example.baseplate.platform.adapter.out.persistence.OpenAppRepository;
import com.example.baseplate.platform.config.PlatformProperties;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 开放平台访问令牌：与内部用户 JWT 分离签名域，不共用声明结构。
 *
 * <p>令牌有两种主体：</p>
 * <ul>
 *   <li>{@code client_credentials}：{@code sub=appKey}，没有 uid，只代表应用；</li>
 *   <li>{@code authorization_code}：额外带 {@code uid}、{@code uname}，代表「某应用代某用户」。</li>
 * </ul>
 * <p>业务接口要不要求用户主体，看 {@code @OpenApi(subject=...)}，不看令牌本身——
 * 令牌只是陈述事实，目录才是规则。</p>
 */
public final class OpenTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final PlatformProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenTokenService(PlatformProperties properties) {
        this.properties = properties;
        this.secret = properties.getTokenSecret().getBytes(StandardCharsets.UTF_8);
    }

    public record IssuedToken(String accessToken, long expiresIn, String scope) {
    }

    public record TokenClaims(String appKey, String tenant, String scope, String jti,
                              String userId, String username,
                              long issuedAt, long expiresAt) {

        /** 是否代表真实用户；false 表示纯应用身份。 */
        public boolean userBound() {
            return userId != null && !userId.isBlank();
        }
    }

    /** 纯应用身份令牌。 */
    public IssuedToken issue(OpenAppRepository.SecretRow app, String scope) {
        return issue(app, scope, null, null);
    }

    /** 带用户身份的令牌：授权码换来的才允许走这一支。 */
    public IssuedToken issue(OpenAppRepository.SecretRow app, String scope,
                             String userId, String username) {
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("sub", app.appKey());
            claims.put("tenant", app.tenantId());
            claims.put("scope", scope);
            claims.put("typ", "openapi");
            if (userId != null && !userId.isBlank()) {
                claims.put("uid", userId);
                claims.put("uname", username);
            }
            claims.put("iat", now);
            claims.put("exp", now + properties.getTokenTtl().toSeconds());
            String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return new IssuedToken(signingInput + "." + b64(hmac(signingInput)),
                    properties.getTokenTtl().toSeconds(), scope);
        } catch (Exception e) {
            throw new IllegalStateException("开放平台令牌签发失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public TokenClaims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("令牌格式不合法");
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("令牌签名不合法");
            }
            Map<String, Object> claims = objectMapper.readValue(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), Map.class);
            long exp = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new IllegalArgumentException("令牌已过期");
            }
            if (!"openapi".equals(claims.get("typ"))) {
                throw new IllegalArgumentException("令牌类型不匹配");
            }
            return new TokenClaims(String.valueOf(claims.get("sub")),
                    String.valueOf(claims.get("tenant")),
                    claims.get("scope") == null ? "" : String.valueOf(claims.get("scope")),
                    String.valueOf(claims.get("jti")),
                    claims.get("uid") == null ? null : String.valueOf(claims.get("uid")),
                    claims.get("uname") == null ? null : String.valueOf(claims.get("uname")),
                    ((Number) claims.get("iat")).longValue(), exp);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌校验失败");
        }
    }

    /** 授权码 / 刷新令牌这类一次性凭证：256 bit 随机，Base64URL 无填充。 */
    public static String newOpaqueToken() {
        byte[] buffer = new byte[32];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** 刷新令牌只存哈希：库被拖走也换不出新令牌。 */
    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("令牌哈希失败", e);
        }
    }

    private byte[] hmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public List<String> scopesOf(String scope) {
        return scope == null || scope.isBlank() ? List.of() : List.of(scope.split("[,\\s]+"));
    }
}
