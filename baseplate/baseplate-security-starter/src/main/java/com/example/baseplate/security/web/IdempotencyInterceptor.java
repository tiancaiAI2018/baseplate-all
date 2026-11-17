package com.example.baseplate.security.web;

import com.example.baseplate.api.annotation.Idempotent;
import com.example.baseplate.api.port.out.IdempotencyStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 幂等：必须带 Idempotency-Key 头。重复请求返回首次结果，首次未完成时返回 409。
 * 结果缓存的是真实响应体，不是「假装成功」。
 */
public final class IdempotencyInterceptor implements HandlerInterceptor {

    private static final String KEY_ATTR = "baseplate.idempotencyKey";
    private final IdempotencyStore store;
    private final Duration ttl;

    public IdempotencyInterceptor(IdempotencyStore store, Duration ttl) {
        this.store = store;
        this.ttl = ttl;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Idempotent annotation = annotationOf(handler);
        if (annotation == null) {
            return true;
        }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            response.setStatus(400);
            response.setContentType("application/problem+json;charset=UTF-8");
            response.getWriter().write("{\"status\":400,\"code\":\"IDEMPOTENCY_KEY_REQUIRED\","
                    + "\"detail\":\"该接口要求 Idempotency-Key 请求头\"}");
            return false;
        }
        String scopedKey = (annotation.key().isBlank() ? request.getRequestURI() : annotation.key()) + ":" + key;
        IdempotencyStore.BeginResult result = store.begin("default", scopedKey, ttl);
        if (!result.acquired()) {
            if (result.hasCachedResult()) {
                response.setStatus(200);
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Idempotent-Replay", "true");
                response.getWriter().write(result.cachedResult());
            } else {
                response.setStatus(409);
                response.setContentType("application/problem+json;charset=UTF-8");
                response.getWriter().write("{\"status\":409,\"code\":\"REQUEST_IN_PROGRESS\","
                        + "\"detail\":\"相同 Idempotency-Key 的请求正在处理中\"}");
            }
            return false;
        }
        request.setAttribute(KEY_ATTR, scopedKey);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object scopedKey = request.getAttribute(KEY_ATTR);
        if (scopedKey == null) {
            return;
        }
        String key = String.valueOf(scopedKey);
        int status = response.getStatus();
        if (ex != null || status >= 400) {
            store.release("default", key);
            return;
        }
        String body = "";
        if (response instanceof ContentCachingResponseWrapper wrapper) {
            body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        store.finish("default", key, body);
    }

    private Idempotent annotationOf(Object handler) {
        if (handler instanceof HandlerMethod method) {
            Idempotent annotation = method.getMethodAnnotation(Idempotent.class);
            return annotation != null ? annotation : method.getBeanType().getAnnotation(Idempotent.class);
        }
        return null;
    }
}
