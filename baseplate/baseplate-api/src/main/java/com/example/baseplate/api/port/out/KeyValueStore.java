package com.example.baseplate.api.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * 单键 KV，带 TTL。
 * 契约：单键原子；不承诺跨键事务。内存实现必须在 Capability 中声明「非分布式」。
 * 所有 key 必须由调用方带上 tenant 维度。
 */
public interface KeyValueStore {
    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    boolean delete(String key);
}
