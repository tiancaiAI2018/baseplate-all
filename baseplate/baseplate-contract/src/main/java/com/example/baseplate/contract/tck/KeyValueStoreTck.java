package com.example.baseplate.contract.tck;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.KeyValueStore;
import com.example.baseplate.contract.fixture.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-KV-01..04：任何 KeyValueStore 实现（内存/Redis）都必须通过。 */
public abstract class KeyValueStoreTck {

    protected MutableClock clock;
    protected KeyValueStore store;

    protected abstract KeyValueStore newStore(Clock clock);

    @BeforeEach
    protected void setUpTck() {
        clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
        store = newStore(clock);
    }

    @Test
    protected void putThenGetRoundTrip() {
        store.put("t1:order:1", "v1", Duration.ofMinutes(10));
        assertEquals("v1", store.get("t1:order:1").orElseThrow());
    }

    @Test
    protected void ttlExpiresValue() {
        store.put("t1:order:2", "v2", Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(31));
        assertTrue(store.get("t1:order:2").isEmpty());
    }

    @Test
    protected void deleteIsIdempotent() {
        store.put("t1:order:3", "v3", Duration.ofMinutes(10));
        assertTrue(store.delete("t1:order:3"));
        store.delete("t1:order:3");
        assertTrue(store.get("t1:order:3").isEmpty());
    }

    @Test
    protected void differentKeysAreIsolated() {
        store.put("t1:order:4", "a", Duration.ofMinutes(10));
        store.put("t2:order:4", "b", Duration.ofMinutes(10));
        assertEquals("a", store.get("t1:order:4").orElseThrow());
        assertEquals("b", store.get("t2:order:4").orElseThrow());
        assertFalse(store.get("t3:order:4").isPresent());
    }
}
