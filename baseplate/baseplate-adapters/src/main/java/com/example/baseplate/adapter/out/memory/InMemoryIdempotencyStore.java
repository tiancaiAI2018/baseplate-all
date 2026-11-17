package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 幂等占位：putIfAbsent 抢占位；过期占位用 replace 原子替换。 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(String resultJson, Instant expireAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public BeginResult begin(String tenant, String key, Duration ttl) {
        String composite = tenant + "|" + key;
        Instant now = clock.now();
        Entry fresh = new Entry(null, now.plus(ttl));

        Entry existing = entries.putIfAbsent(composite, fresh);
        if (existing == null) {
            return new BeginResult(true, null);
        }
        if (!now.isBefore(existing.expireAt())) {
            if (entries.replace(composite, existing, fresh)) {
                return new BeginResult(true, null);
            }
            existing = entries.get(composite);
        }
        return new BeginResult(false, existing == null ? null : existing.resultJson());
    }

    @Override
    public void finish(String tenant, String key, String resultJson) {
        String composite = tenant + "|" + key;
        Entry current = entries.get(composite);
        Instant expireAt = current == null ? clock.now().plus(Duration.ofMinutes(10)) : current.expireAt();
        entries.put(composite, new Entry(resultJson, expireAt));
    }

    @Override
    public void release(String tenant, String key) {
        entries.remove(tenant + "|" + key);
    }
}
