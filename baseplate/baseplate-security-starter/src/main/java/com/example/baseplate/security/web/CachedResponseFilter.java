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
 * <p>流式响应必须跳过：{@link ContentCachingResponseWrapper} 会把流式响应
 * 缓冲到结束，只发出第一帧并给响应写死 Content-Length，后续内容全部丢失。
 * 包括 SSE（对话/审批/外部调用结果）和流式导出（观测导出）：导出的
 * {@code StreamingResponseBody} 在异步线程写出，wrapper 会在主线程返回时
 * 就提交响应，导致文件只剩一个 '['。</p>
 */
public final class CachedResponseFilter extends OncePerRequestFilter {

    private static final Pattern STREAMING_PATHS = Pattern.compile(
            "^/(api|openapi)/(agent/chat/stream"
            + "|agent/hitl/[^/]+/decision"
            + "|agent/external-calls/[^/]+/result"
            + "|agent/observe/export"
            + "|agent/observe/sessions/[^/]+/export)$");

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
