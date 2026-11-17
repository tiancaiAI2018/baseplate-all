package com.example.baseplate.adapter.autoconfigure;

import com.example.baseplate.adapter.out.redis.RedisCacheProvider;
import com.example.baseplate.adapter.out.redis.RedisIdempotencyStore;
import com.example.baseplate.adapter.out.redis.RedisKeyValueStore;
import com.example.baseplate.adapter.out.redis.RedisNonceStore;
import com.example.baseplate.adapter.out.redis.RedisOneTimeTokenStore;
import com.example.baseplate.adapter.out.redis.RedisRateLimiter;
import com.example.baseplate.api.port.out.CacheProvider;
import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.api.port.out.IdempotencyStore;
import com.example.baseplate.api.port.out.KeyValueStore;
import com.example.baseplate.api.port.out.NonceStore;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.runtime.config.BaseplateProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 端口实现：baseplate.redis.enabled=true 且 classpath 有 Redis 时生效。 */
@AutoConfiguration(before = MemoryPortsAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "baseplate.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisPortsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeyValueStore keyValueStore(StringRedisTemplate redis, BaseplateProperties properties) {
        return new RedisKeyValueStore(redis, properties.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public NonceStore nonceStore(StringRedisTemplate redis, BaseplateProperties properties) {
        return new RedisNonceStore(redis, properties.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public OneTimeTokenStore oneTimeTokenStore(StringRedisTemplate redis, IdGenerator ids,
                                               BaseplateProperties properties) {
        return new RedisOneTimeTokenStore(redis, ids, properties.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(StringRedisTemplate redis, BaseplateProperties properties) {
        return new RedisRateLimiter(redis, properties.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(StringRedisTemplate redis, BaseplateProperties properties) {
        return new RedisIdempotencyStore(redis, properties.getRedis().getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheProvider cacheProvider(StringRedisTemplate redis, BaseplateProperties properties) {
        return new RedisCacheProvider(redis, properties.getRedis().getKeyPrefix());
    }
}
