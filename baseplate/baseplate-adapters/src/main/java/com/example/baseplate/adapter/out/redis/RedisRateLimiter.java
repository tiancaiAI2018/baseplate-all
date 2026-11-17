package com.example.baseplate.adapter.out.redis;

import com.example.baseplate.api.port.out.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * 固定窗口限流：INCR + PEXPIRE 必须在同一个 Lua 脚本里，否则
 * 「INCR 成功但 EXPIRE 失败」会留下永不过期的计数器。
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final String RATE_LIMIT_LUA =
            "local current = redis.call('INCR', KEYS[1])\n"
            + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n"
            + "return current";

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(RATE_LIMIT_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisRateLimiter(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public boolean tryAcquire(String tenant, String dimension, int limit, Duration window) {
        String key = prefix + "rl:" + tenant + ":" + dimension;
        Long current = redis.execute(SCRIPT, List.of(key), String.valueOf(window.toMillis()));
        return current != null && current <= limit;
    }
}
