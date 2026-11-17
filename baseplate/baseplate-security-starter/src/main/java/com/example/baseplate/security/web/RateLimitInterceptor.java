package com.example.baseplate.security.web;

import com.example.baseplate.api.annotation.RateLimit;
import com.example.baseplate.api.exception.RateLimitedException;
import com.example.baseplate.api.port.out.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 注解式限流：维度默认「用户或 IP + 方法签名」。
 * 单机内存实现只适合本地；多实例必须 Redis + Lua 原子计数（适配器已实现）。
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimit annotation = method.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RateLimit.class);
        }
        if (annotation == null) {
            return true;
        }
        String dimension = annotation.key().isBlank()
                ? method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
                : annotation.key();
        String subject = RateLimitSubject.of(request);
        boolean acquired = limiter.tryAcquire("default", dimension + ":" + subject,
                annotation.limit(), Duration.ofSeconds(annotation.windowSeconds()));
        if (!acquired) {
            throw new RateLimitedException("请求过于频繁，请稍后再试");
        }
        return true;
    }
}
