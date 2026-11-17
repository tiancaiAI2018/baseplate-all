package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.api.port.out.OneTimeTokenStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 一次性链接：用途域参与校验；remove(key, value) 保证并发下只有一个消费成功。 */
public final class InMemoryOneTimeTokenStore implements OneTimeTokenStore {

    private record Entry(String purpose, String subject, Instant expireAt) {
    }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Clock clock;
    private final IdGenerator ids;

    public InMemoryOneTimeTokenStore(Clock clock, IdGenerator ids) {
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public String issue(String purpose, String subject, Duration ttl) {
        String jti = ids.next();
        tokens.put(jti, new Entry(purpose, subject, clock.now().plus(ttl)));
        return jti;
    }

    @Override
    public Optional<String> consume(String purpose, String jti) {
        Entry entry = tokens.get(jti);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.now().isBefore(entry.expireAt())) {
            tokens.remove(jti, entry);
            return Optional.empty();
        }
        if (!entry.purpose().equals(purpose)) {
            return Optional.empty();
        }
        if (!tokens.remove(jti, entry)) {
            return Optional.empty();
        }
        return Optional.of(entry.subject());
    }
}
