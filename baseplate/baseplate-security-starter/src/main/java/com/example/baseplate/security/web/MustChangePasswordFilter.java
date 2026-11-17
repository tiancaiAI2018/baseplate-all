package com.example.baseplate.security.web;

import com.example.baseplate.api.model.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 首登/过期强制改密拦截：认证成功但密码状态未变更时，只放行改密、登出、取当前用户。
 * 只在前端弹窗提醒是无效的——用户可以直接调其它接口绕过。
 */
public final class MustChangePasswordFilter extends OncePerRequestFilter {

    private final Set<String> allowed;

    public MustChangePasswordFilter(String allowedPaths) {
        this.allowed = Arrays.stream(allowedPaths.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CurrentUser user = CurrentUserProviderImpl.get();
        if (user != null && user.mustChangePassword() && !isAllowed(request)) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                    + "\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"detail\":\"首次登录或密码已过期，必须先修改密码\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return allowed.stream().anyMatch(path::equals);
    }
}
