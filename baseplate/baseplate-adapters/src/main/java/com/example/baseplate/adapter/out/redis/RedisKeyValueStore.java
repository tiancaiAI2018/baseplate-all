package com.example.baseplate.adapter.out.redis;

import com.example.baseplate.api.port.out.KeyValueStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/** Redis KV：分布式、单键原子。 */
public final class RedisKeyValueStore implements KeyValueStore {

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisKeyValueStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(prefix + key, value, ttl);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(prefix + key));
    }

    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(prefix + key));
    }
}
