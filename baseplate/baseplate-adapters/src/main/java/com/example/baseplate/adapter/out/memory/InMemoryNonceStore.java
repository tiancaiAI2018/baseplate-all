package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.NonceStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例 nonce 去重：putIfAbsent 保证同 key 只有一个赢。 */
public final class InMemoryNonceStore implements NonceStore {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryNonceStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryConsume(String tenant, String appKey, String nonce, Duration ttl) {
        String key = tenant + "|" + appKey + "|" + nonce;
        Instant now = clock.now();
        Instant newExpiry = now.plus(ttl);

        Instant previous = seen.putIfAbsent(key, newExpiry);
        if (previous == null) {
            return true;
        }
        if (!now.isBefore(previous)) {
            return seen.replace(key, previous, newExpiry);
        }
        return false;
    }
}
