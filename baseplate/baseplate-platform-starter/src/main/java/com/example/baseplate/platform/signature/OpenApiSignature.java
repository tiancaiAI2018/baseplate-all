package com.example.baseplate.platform.signature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 请求签名：token 证明「你是谁」，签名证明「这次请求没被改过」。
 * 待签串包含方法、路径、排序后的查询参数、时间戳、nonce、请求体摘要，
 * 任何一项被篡改都会验签失败。
 */
public final class OpenApiSignature {

    private OpenApiSignature() {
    }

    public static String canonical(String method, String path, Map<String, String[]> query,
                                   String timestamp, String nonce, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.toUpperCase()).append('\n');
        sb.append(path).append('\n');
        sb.append(canonicalQuery(query)).append('\n');
        sb.append(timestamp).append('\n');
        sb.append(nonce).append('\n');
        sb.append(sha256Hex(body == null ? new byte[0] : body));
        return sb.toString();
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    public static boolean verify(String secret, String canonical, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, canonical).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonicalQuery(Map<String, String[]> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        TreeMap<String, String[]> sorted = new TreeMap<>(query);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, values) -> {
            if (values == null || values.length == 0) {
                append(sb, key, "");
                return;
            }
            for (String value : values) {
                append(sb, key, value);
            }
        });
        return sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "";
    }

    private static void append(StringBuilder sb, String key, String value) {
        sb.append(encode(key)).append('=').append(encode(value)).append('&');
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
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
