package com.example.baseplate.contract.tck;

import com.example.baseplate.api.port.out.CacheProvider;
import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.contract.fixture.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-CACHE-01..03。 */
public abstract class CacheProviderTck {

    protected MutableClock clock;
    protected CacheProvider cache;

    protected abstract CacheProvider newCache(Clock clock);

    @BeforeEach
    protected void setUpTck() {
        clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
        cache = newCache(clock);
    }

    @Test
    protected void putThenGetWithType() {
        cache.put("t1:dict:A", java.util.List.of("a", "b"), Duration.ofMinutes(5));
        Object value = cache.get("t1:dict:A", Object.class).orElseThrow();
        assertEquals(java.util.List.of("a", "b"), value);
    }

    @Test
    protected void ttlExpires() {
        cache.put("t1:dict:B", "v", Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(11));
        assertTrue(cache.get("t1:dict:B", String.class).isEmpty());
    }

    @Test
    protected void evictWorks() {
        cache.put("t1:dict:C", "v", Duration.ofMinutes(5));
        cache.evict("t1:dict:C");
        assertTrue(cache.get("t1:dict:C", String.class).isEmpty());
    }
}
