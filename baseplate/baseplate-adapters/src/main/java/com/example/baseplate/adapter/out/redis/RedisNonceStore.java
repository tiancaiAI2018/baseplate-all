package com.example.baseplate.adapter.out.redis;

import com.example.baseplate.api.port.out.NonceStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** SET NX EX：原子去重，键含 tenant + appKey 维度。 */
public final class RedisNonceStore implements NonceStore {

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisNonceStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public boolean tryConsume(String tenant, String appKey, String nonce, Duration ttl) {
        String key = prefix + "nonce:" + tenant + ":" + appKey + ":" + nonce;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", ttl));
    }
}
