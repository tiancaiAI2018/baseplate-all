package com.example.baseplate.contract.tck;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdempotencyStore;
import com.example.baseplate.contract.fixture.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-IDEMP-01..03。 */
public abstract class IdempotencyStoreTck {

    protected MutableClock clock;
    protected IdempotencyStore store;

    protected abstract IdempotencyStore newStore(Clock clock);

    @BeforeEach
    protected void setUpTck() {
        clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
        store = newStore(clock);
    }

    @Test
    protected void firstCallAcquires() {
        var result = store.begin("t1", "req-1", Duration.ofMinutes(10));
        assertTrue(result.acquired());
        assertNull(result.cachedResult());
    }

    @Test
    protected void repeatReturnsCachedResultAfterFinish() {
        store.begin("t1", "req-2", Duration.ofMinutes(10));
        store.finish("t1", "req-2", "{\"ok\":true}");
        var second = store.begin("t1", "req-2", Duration.ofMinutes(10));
        assertFalse(second.acquired());
        assertEquals("{\"ok\":true}", second.cachedResult());
    }

    @Test
    protected void pendingRepeatIsNotTreatedAsSuccess() {
        store.begin("t1", "req-3", Duration.ofMinutes(10));
        var second = store.begin("t1", "req-3", Duration.ofMinutes(10));
        assertFalse(second.acquired());
        assertFalse(second.hasCachedResult());
    }

    @Test
    protected void releaseAllowsRetry() {
        store.begin("t1", "req-4", Duration.ofMinutes(10));
        store.release("t1", "req-4");
        assertTrue(store.begin("t1", "req-4", Duration.ofMinutes(10)).acquired());
    }
}
