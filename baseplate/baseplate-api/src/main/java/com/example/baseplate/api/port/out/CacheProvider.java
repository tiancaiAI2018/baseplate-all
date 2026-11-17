package com.example.baseplate.api.port.out;

import java.time.Duration;
import java.util.Optional;

/** 类型化缓存；key 必须含 tenant 维度，避免共享表隔离了、缓存串了。 */
public interface CacheProvider {
    <T> Optional<T> get(String key, Class<T> type);

    <T> void put(String key, T value, Duration ttl);

    void evict(String key);
}
