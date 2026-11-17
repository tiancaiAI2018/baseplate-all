package com.example.baseplate.security.web;

import com.example.baseplate.api.annotation.OneTimeLink;
import com.example.baseplate.api.exception.AuthenticationException;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/** 一次性链接：令牌只能消费一次，消费即失效（Redis Lua 原子 GET+DEL）。 */
public final class OneTimeLinkInterceptor implements HandlerInterceptor {

    private final OneTimeTokenStore store;

    public OneTimeLinkInterceptor(OneTimeTokenStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        OneTimeLink annotation = method.getMethodAnnotation(OneTimeLink.class);
        if (annotation == null) {
            return true;
        }
        String token = request.getHeader("X-One-Time-Token");
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("缺少一次性令牌");
        }
        String purpose = annotation.purpose().isBlank() ? method.getMethod().getName() : annotation.purpose();
        Optional<String> subject = store.consume(purpose, token);
        if (subject.isEmpty()) {
            throw new AuthenticationException("一次性令牌无效或已使用");
        }
        request.setAttribute("baseplate.oneTimeSubject", subject.get());
        return true;
    }
}
