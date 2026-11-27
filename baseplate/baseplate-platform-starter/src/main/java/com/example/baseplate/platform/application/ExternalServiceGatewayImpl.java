package com.example.baseplate.platform.application;

import com.example.baseplate.api.port.out.ExternalServiceGateway;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.domain.ExternalEndpoint;
import com.example.baseplate.platform.domain.ExternalService;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** 外部服务网关：地址与凭证只从注册表取，调用方无法传 URL 或密钥。 */
public final class ExternalServiceGatewayImpl implements ExternalServiceGateway {

    private static final int MAX_RESPONSE = 40_000;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ExternalServiceRegistry registry;
    private final SecretCipher cipher;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalServiceGatewayImpl(ExternalServiceRegistry registry, SecretCipher cipher) {
        this.registry = registry;
        this.cipher = cipher;
    }

    @Override
    public String call(String tenant, String serviceCode, String endpointCode,
                       Map<String, Object> params, Map<String, String> headers) {
        ExternalService service = registry.find(serviceCode);
        ExternalEndpoint endpoint = registry.endpoint(serviceCode, endpointCode);
        if (service == null || endpoint == null) {
            throw new IllegalArgumentException("外部服务或端点不存在: " + serviceCode + "/" + endpointCode);
        }

        Map<String, Object> remaining = new LinkedHashMap<>(
                params == null ? Map.of() : params);
        String path = resolvePath(endpoint.path(), remaining);
        String method = endpoint.method() == null ? "GET" : endpoint.method().toUpperCase();

        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(service.baseUrl() + path))
                .timeout(Duration.ofSeconds(15));
        service.staticHeaders().forEach(builder::header);
        injectCredential(builder, service);
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (value != null) builder.header(key, value);
            });
        }

        String idempotencyKey = removeString(remaining, "idempotencyKey");
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }

        try {
            if ("GET".equals(method)) {
                builder.GET();
                String query = queryOf(remaining);
                if (!query.isBlank()) {
                    builder.uri(URI.create(service.baseUrl() + path + "?" + query));
                }
            } else if ("POST".equals(method)) {
                Object body = remaining.size() == 1 && remaining.containsKey("draft")
                        ? remaining.get("draft") : remaining;
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                throw new IllegalArgumentException("外部端点只支持 GET/POST: " + method);
            }
            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (body.length() > MAX_RESPONSE) {
                body = body.substring(0, MAX_RESPONSE) + "…(已截断)";
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("第三方返回 HTTP " + response.statusCode() + ": " + body);
            }
            return body;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用外部服务失败: " + serviceCode + "/" + endpointCode
                    + " - " + e.getMessage(), e);
        }
    }

    private void injectCredential(HttpRequest.Builder builder, ExternalService service) {
        if ("NONE".equalsIgnoreCase(service.authType()) || service.credentialEnc() == null
                || service.credentialEnc().isBlank()) {
            return;
        }
        String secret = cipher.decrypt(service.credentialEnc());
        if ("BEARER".equalsIgnoreCase(service.authType())) {
            builder.header("Authorization", "Bearer " + secret);
        } else if ("API_KEY".equalsIgnoreCase(service.authType())) {
            builder.header(service.credentialHeader() == null ? "X-Api-Key" : service.credentialHeader(), secret);
        }
    }

    private static String resolvePath(String path, Map<String, Object> params) {
        String result = path;
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(params).entrySet()) {
            String token = "{" + entry.getKey() + "}";
            if (result.contains(token)) {
                result = result.replace(token, URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
                params.remove(entry.getKey());
            }
        }
        return result;
    }

    private static String removeString(Map<String, Object> params, String key) {
        Object value = params.remove(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String queryOf(Map<String, Object> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((key, value) -> {
            if (value != null) {
                joiner.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            }
        });
        return joiner.toString();
    }
}
