package com.example.baseplate.audit.adapter.in.web;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.audit.application.AuditWriter;
import com.example.baseplate.security.web.CurrentUserProviderImpl;
import com.example.baseplate.security.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 访问审计：只记 /api 与 /openapi，静态资源与探针不刷表。 */
public final class AccessLogFilter extends OncePerRequestFilter {

    private final AuditWriter writer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AccessLogFilter(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/") || path.startsWith("/openapi/"))
                || path.startsWith("/api/audit/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUser user = CurrentUserProviderImpl.get();
            Object traceId = request.getAttribute(TraceIdFilter.ATTR);
            String ip = clientIp(request);
            String agent = request.getHeader("User-Agent");
            int duration = (int) (System.currentTimeMillis() - start);
            // 开放 API 由 OpenApiAuthFilter 在链上写入应用身份；第三方调用没有登录用户，
            // 用应用名当操作人，否则审计里只能看到一个匿名的 /openapi/... 请求。
            String appKey = attr(request, "openapi.appKey");
            String appName = attr(request, "openapi.appName");
            String apiCode = attr(request, "openapi.apiCode");
            String sandbox = attr(request, "openapi.sandbox");
            // 第三方请求优先记应用：即便它带了 Bearer 头被 JWT 过滤器认成匿名主体，
            // 也不能顶掉“哪个应用在调用”这个信息。
            String actor;
            if (appKey != null || appName != null) {
                actor = appName != null ? appName : "app:" + appKey;
            } else if (user != null && user.username() != null) {
                actor = user.username();
            } else {
                actor = "anonymous";
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("source", appKey != null || request.getRequestURI().startsWith("/openapi/")
                    ? "THIRD_PARTY" : "INTERNAL");
            if (appKey != null) {
                detail.put("appKey", appKey);
                detail.put("appName", appName);
            }
            if (apiCode != null) {
                detail.put("apiCode", apiCode);
            }
            if (sandbox != null) {
                detail.put("sandbox", sandbox);
            }
            detail.put("query", request.getQueryString());
            // 第三方没有登录用户，租户只能取应用所属租户，否则这行在控制台按租户过滤时查不到
            String tenantId = user != null ? user.tenantId() : attr(request, "openapi.appTenant");
            writer.access(tenantId,
                    traceId == null ? null : String.valueOf(traceId),
                    actor,
                    request.getMethod(), request.getRequestURI(),
                    String.valueOf(response.getStatus()), ip,
                    agent == null ? null : agent.substring(0, Math.min(agent.length(), 500)),
                    duration, truncate(request.getQueryString(), 1000), toJson(detail));
        }
    }

    private String attr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    /** ::1 与 0:0:0:0:0:0:0:1 是同一个回环地址，直接落库会让人以为来源异常。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return normalize(forwarded.split(",")[0].trim());
        }
        return normalize(request.getRemoteAddr());
    }

    private String normalize(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        if (ip != null && ip.startsWith("::ffff:")) {
            return ip.substring(7);
        }
        return ip;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            return "{}";
        }
    }
}
