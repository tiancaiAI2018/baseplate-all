package com.example.baseplate.api.port.out;

import java.time.Duration;

/** 限流：多实例必须 Redis + Lua 原子脚本；内存实现只对单实例有效。 */
public interface RateLimiter {
    boolean tryAcquire(String tenant, String dimension, int limit, Duration window);
}
