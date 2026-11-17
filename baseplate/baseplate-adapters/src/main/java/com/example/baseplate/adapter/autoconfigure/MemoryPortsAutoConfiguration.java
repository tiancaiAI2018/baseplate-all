package com.example.baseplate.adapter.autoconfigure;

import com.example.baseplate.adapter.out.memory.InMemoryCacheProvider;
import com.example.baseplate.adapter.out.memory.InMemoryIdempotencyStore;
import com.example.baseplate.adapter.out.memory.InMemoryKeyValueStore;
import com.example.baseplate.adapter.out.memory.InMemoryNonceStore;
import com.example.baseplate.adapter.out.memory.InMemoryOneTimeTokenStore;
import com.example.baseplate.adapter.out.memory.InMemoryRateLimiter;
import com.example.baseplate.api.port.out.CacheProvider;
import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.api.port.out.IdempotencyStore;
import com.example.baseplate.api.port.out.KeyValueStore;
import com.example.baseplate.api.port.out.NonceStore;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import com.example.baseplate.api.port.out.RateLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 内存降级：没有 Redis 时必须仍能启动并通过核心用例。
 * 这些实现声明为非分布式，不能用于多实例生产。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "baseplate.redis", name = "fallback-to-memory",
        havingValue = "true", matchIfMissing = true)
public class MemoryPortsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeyValueStore inMemoryKeyValueStore(Clock clock) {
        return new InMemoryKeyValueStore(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public NonceStore inMemoryNonceStore(Clock clock) {
        return new InMemoryNonceStore(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public OneTimeTokenStore inMemoryOneTimeTokenStore(Clock clock, IdGenerator ids) {
        return new InMemoryOneTimeTokenStore(clock, ids);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter inMemoryRateLimiter(Clock clock) {
        return new InMemoryRateLimiter(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore inMemoryIdempotencyStore(Clock clock) {
        return new InMemoryIdempotencyStore(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheProvider inMemoryCacheProvider(Clock clock) {
        return new InMemoryCacheProvider(clock);
    }
}
