package com.example.baseplate.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 响应体缓存：幂等重放要返回首次的真实响应，不是空壳。
 * 只包一层 wrapper，不改变响应内容。
 *
 * <p>SSE 端点必须跳过：{@link ContentCachingResponseWrapper} 会把流式响应
 * 缓冲到结束，只发出第一帧并给响应写死 Content-Length，后续 token 全部丢失。</p>
 */
public final class CachedResponseFilter extends OncePerRequestFilter {

    private static final Pattern STREAMING_PATHS = Pattern.compile(
            "^/api/(agent/chat/stream|agent/hitl/[^/]+/decision|agent/external-calls/[^/]+/result)$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return STREAMING_PATHS.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            wrapper.copyBodyToResponse();
        }
    }
}
