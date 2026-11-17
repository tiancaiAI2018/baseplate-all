package com.example.baseplate.security.jwt;

import com.example.baseplate.api.exception.AuthenticationException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * HS256 校验：签名、有效期、结构三段都要查。
 * 失败一律抛 AuthenticationException，对外不区分原因。
 */
public final class JwtVerifier {

    private final byte[] secret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtVerifier(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public JwtClaims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new AuthenticationException("令牌格式不合法");
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new AuthenticationException("令牌签名不合法");
            }
            Map<String, Object> claims = objectMapper.readValue(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), Map.class);
            long exp = asLong(claims.get("exp"));
            if (Instant.now().getEpochSecond() >= exp) {
                throw new AuthenticationException("令牌已过期");
            }
            return new JwtClaims(
                    str(claims.get("jti")),
                    str(claims.get("sub")),
                    str(claims.get("tenant")),
                    str(claims.get("username")),
                    toSet(claims.get("roles")),
                    toSet(claims.get("permissions")),
                    Boolean.TRUE.equals(claims.get("mustChange")),
                    asLong(claims.getOrDefault("iat", 0)),
                    exp);
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("令牌校验失败");
        }
    }

    private byte[] hmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> toSet(Object value) {
        if (value instanceof List<?> list) {
            java.util.Set<String> result = new LinkedHashSet<>();
            list.forEach(item -> result.add(String.valueOf(item)));
            return result;
        }
        return java.util.Set.of();
    }
}
