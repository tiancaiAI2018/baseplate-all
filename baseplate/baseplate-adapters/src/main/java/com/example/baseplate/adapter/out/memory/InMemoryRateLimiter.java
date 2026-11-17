package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.RateLimiter;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固定窗口限流（单实例）。
 * 能力声明：非分布式。N 个实例部署时实际额度会被放大 N 倍，
 * 多实例必须用 Redis + Lua 实现。
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private record Window(Instant resetAt, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String tenant, String dimension, int limit, Duration window) {
        String key = tenant + "|" + dimension;
        Instant now = clock.now();
        Window current = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !now.isBefore(existing.resetAt())) {
                return new Window(now.plus(window), 1);
            }
            return new Window(existing.resetAt(), existing.count() + 1);
        });
        return current.count() <= limit;
    }
}
