package com.example.baseplate.security.web;

import com.example.baseplate.api.exception.AuthenticationException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.security.jwt.JwtClaims;
import com.example.baseplate.security.jwt.JwtVerifier;
import com.example.baseplate.security.jwt.TokenBlacklist;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JWT 入站校验：Bearer 令牌 → SecurityContext + CurrentUser。
 * 无令牌直接放行给授权规则（公开端点可用），有令牌但非法则 401 立即返回。
 */
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtVerifier verifier;
    private final TokenBlacklist blacklist;

    public JwtAuthenticationFilter(JwtVerifier verifier, TokenBlacklist blacklist) {
        this.verifier = verifier;
        this.blacklist = blacklist;
    }

    /**
     * /openapi/** 有独立的鉴权链（AppKey + 签名 + 开放令牌），内部 JWT 不参与。
     * 否则第三方带着自己的 Bearer 令牌调开放接口，会先被这里当成非法内部令牌 401 掉。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/openapi/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            JwtClaims claims = verifier.verify(header.substring(7).trim());
            if (blacklist.isRevoked(claims)) {
                throw new AuthenticationException("令牌已注销");
            }
            Set<String> authorities = new LinkedHashSet<>();
            claims.roles().forEach(role -> authorities.add("ROLE_" + role));
            authorities.addAll(claims.permissions());
            var authentication = new UsernamePasswordAuthenticationToken(
                    claims.username(), null,
                    authorities.stream().map(SimpleGrantedAuthority::new).toList());
            SecurityContextHolder.getContext().setAuthentication(authentication);

            CurrentUserProviderImpl.set(new CurrentUser(
                    claims.subject(), claims.tenant(), claims.username(), claims.roles(),
                    claims.permissions(), claims.mustChangePassword()));
            request.setAttribute("baseplate.claims", claims);
            chain.doFilter(request, response);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String traceId = String.valueOf(request.getAttribute(TraceIdFilter.ATTR));
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\","
                    + "\"status\":401,\"code\":\"AUTH_FAILED\",\"detail\":\"" + e.getMessage()
                    + "\",\"traceId\":\"" + traceId + "\"}");
        } finally {
            CurrentUserProviderImpl.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
