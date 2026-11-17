package com.example.baseplate.security.web;

import com.example.baseplate.api.model.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;

/** 限流主体：已登录按用户，未登录按来源 IP；两者都不带租户拼接由调用方负责。 */
final class RateLimitSubject {

    private RateLimitSubject() {
    }

    static String of(HttpServletRequest request) {
        CurrentUser user = CurrentUserProviderImpl.get();
        if (user != null) {
            return "u:" + user.userId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
