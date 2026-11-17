package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.CacheProvider;
import com.example.baseplate.api.port.out.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例内存缓存：key 必须含 tenant 维度。 */
public final class InMemoryCacheProvider implements CacheProvider {

    private record Entry(Object value, Instant expireAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCacheProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.now().isBefore(entry.expireAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(type.cast(entry.value()));
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        entries.put(key, new Entry(value, clock.now().plus(ttl)));
    }

    @Override
    public void evict(String key) {
        entries.remove(key);
    }
}
