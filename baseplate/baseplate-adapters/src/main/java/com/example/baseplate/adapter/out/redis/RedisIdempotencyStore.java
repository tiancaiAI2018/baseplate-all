package com.example.baseplate.adapter.out.redis;

import com.example.baseplate.api.port.out.IdempotencyStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** 幂等占位：SET NX 抢占，PENDING 表示首次仍在执行。 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PENDING = "PENDING";

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisIdempotencyStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public BeginResult begin(String tenant, String key, Duration ttl) {
        String composite = key(tenant, key);
        Boolean acquired = redis.opsForValue().setIfAbsent(composite, PENDING, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            return new BeginResult(true, null);
        }
        String value = redis.opsForValue().get(composite);
        if (value == null || PENDING.equals(value)) {
            return new BeginResult(false, null);
        }
        return new BeginResult(false, value);
    }

    @Override
    public void finish(String tenant, String key, String resultJson) {
        redis.opsForValue().set(key(tenant, key), resultJson, Duration.ofMinutes(10));
    }

    @Override
    public void release(String tenant, String key) {
        redis.delete(key(tenant, key));
    }

    private String key(String tenant, String key) {
        return prefix + "idem:" + tenant + ":" + key;
    }
}
