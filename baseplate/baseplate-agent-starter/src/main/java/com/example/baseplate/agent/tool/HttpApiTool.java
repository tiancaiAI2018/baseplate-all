package com.example.baseplate.agent.tool;

import com.example.baseplate.api.model.CurrentUser;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

import tools.jackson.databind.ObjectMapper;

/**
 * 自定义内部工具：控制台配置一个 HTTP 接口，Agent 循环里由服务端直接调用。
 *
 * <p>安全边界：只允许 http/https、拒绝内网/环回地址、固定超时、响应截断，
 * 并且执行前按当前用户校验工具声明的权限码。这是「工具即用户能力」的延伸，
 * 不能因为模型决定调用就越过用户权限。</p>
 */
public final class HttpApiTool extends ToolBase {

    private static final int MAX_RESPONSE = 8000;
    private static final String DEFAULT_TIMEOUT_SECONDS = "15";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String toolCode;
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String permissionCode;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpApiTool(String toolCode, String name, String description,
                       Map<String, Object> inputSchema, Map<String, Object> config,
                       String permissionCode) {
        super(ToolBase.builder()
                .name(toolCode)
                .description(description == null || description.isBlank() ? name : description)
                .inputSchema(inputSchema == null || inputSchema.isEmpty()
                        ? Map.of("type", "object", "properties", Map.of()) : inputSchema)
                .readOnly(false)
                .concurrencySafe(false));
        this.toolCode = toolCode;
        this.permissionCode = permissionCode;
        this.url = str(config.get("url"));
        this.method = str(config.getOrDefault("method", "POST")).toUpperCase();
        this.headers = stringMap(config.get("headers"));
        this.timeout = Duration.ofSeconds(parseLong(
                config.getOrDefault("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)));
        validateUrl(this.url);
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("内部 HTTP 工具只支持 GET/POST: " + method);
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        CurrentUser user = param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get("principal");
        requirePermission(user);

        try {
            URI uri = URI.create("GET".equals(method) ? appendQuery(url, param.getInput()) : url);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
            headers.forEach(builder::header);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(param.getInput()), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (body.length() > MAX_RESPONSE) {
                body = body.substring(0, MAX_RESPONSE) + "…";
            }
            if (response.statusCode() / 100 != 2) {
                return ToolResultBlock.error("HTTP " + response.statusCode() + ": " + body);
            }
            // 2.0.4 的 ToolResultBlock 没有 success(String)，text 等价且按内容判定成功
            return ToolResultBlock.text(body);
        } catch (Exception e) {
            return ToolResultBlock.error("调用 " + toolCode + " 失败: " + e.getMessage());
        }
    }

    private void requirePermission(CurrentUser user) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return;
        }
        if (user == null) {
            throw new IllegalStateException("缺少用户上下文");
        }
        if (user.roles() != null && user.roles().contains("SUPER_ADMIN")) {
            return;
        }
        if (user.permissions() == null || !user.permissions().contains(permissionCode)) {
            throw new IllegalStateException("当前用户缺少权限：" + permissionCode);
        }
    }

    /** 创建工具时也调用一次，坏 URL 不允许入库。 */
    public static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("内部 HTTP 工具必须配置 url");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("url 格式不合法: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("url 只支持 http/https");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isBlank() || "localhost".equals(host) || host.startsWith("127.")
                || host.startsWith("10.") || host.startsWith("192.168.")
                || host.startsWith("169.254.") || isPrivate172(host)) {
            throw new IllegalArgumentException("url 不允许指向内网或环回地址: " + host);
        }
    }

    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String appendQuery(String url, Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return url;
        }
        StringJoiner joiner = new StringJoiner("&");
        input.forEach((key, value) -> {
            if (value != null) {
                joiner.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            }
        });
        return url + (url.contains("?") ? "&" : "?") + joiner;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long parseLong(Object value) {
        try {
            return Math.max(1, Math.min(60, Long.parseLong(String.valueOf(value))));
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return result;
    }
}
