package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.KeyValueStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例内存实现：用于本地开发 / CI / Redis 不可用时的降级。
 * 能力声明：非分布式、单键原子。多实例部署下不能替代 Redis 限流与 nonce 去重。
 */
public final class InMemoryKeyValueStore implements KeyValueStore {

    private record Entry(String value, Instant expireAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryKeyValueStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        entries.put(key, new Entry(value, clock.now().plus(ttl)));
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.now().isBefore(entry.expireAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public boolean delete(String key) {
        return entries.remove(key) != null;
    }
}
