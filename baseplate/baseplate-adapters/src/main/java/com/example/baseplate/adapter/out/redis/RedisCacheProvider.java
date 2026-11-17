package com.example.baseplate.adapter.out.redis;

import com.example.baseplate.api.port.out.CacheProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 缓存：JSON 序列化。
 * 限制：type 必须是具体类；Object.class 会丢类型信息，调用方应传具体类型。
 */
public final class RedisCacheProvider implements CacheProvider {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String prefix;

    public RedisCacheProvider(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        // Boot 4 基线是 Jackson 3（tools.jackson），不注入 Jackson 2 的 ObjectMapper Bean
        this.objectMapper = new ObjectMapper();
        this.prefix = prefix;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = redis.opsForValue().get(cacheKey(key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        try {
            redis.opsForValue().set(cacheKey(key), objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败: " + key, e);
        }
    }

    @Override
    public void evict(String key) {
        redis.delete(cacheKey(key));
    }

    private String cacheKey(String key) {
        return prefix + "cache:" + key;
    }
}
