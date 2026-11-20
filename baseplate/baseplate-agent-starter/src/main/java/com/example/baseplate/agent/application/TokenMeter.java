package com.example.baseplate.agent.application;

import io.agentscope.core.model.ChatUsage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一轮对话的 token 用量累计器：模型层每次调用结束时累加，落库时读取。
 *
 * <p>实例挂在 {@code RuntimeContext} 上，随一次 call 生命周期走，三条入口
 * （正常对话 / HITL 恢复 / 外部工具恢复）各自新建一份，互不串号。</p>
 */
public final class TokenMeter {

    public static final String CONTEXT_KEY = "baseplate.tokenMeter";

    private final AtomicInteger input = new AtomicInteger();
    private final AtomicInteger output = new AtomicInteger();
    private final AtomicInteger cached = new AtomicInteger();

    void add(ChatUsage usage) {
        if (usage == null) {
            return;
        }
        input.addAndGet(usage.getInputTokens());
        output.addAndGet(usage.getOutputTokens());
        cached.addAndGet(usage.getCachedTokens());
    }

    public int inputTokens() {
        return input.get();
    }

    public int outputTokens() {
        return output.get();
    }

    public int cachedTokens() {
        return cached.get();
    }
}
