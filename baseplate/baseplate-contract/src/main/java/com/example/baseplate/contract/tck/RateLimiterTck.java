package com.example.baseplate.contract.tck;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.contract.fixture.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-LIMIT-01..03。多实例下只有 Redis 实现能保证不放大额度。 */
public abstract class RateLimiterTck {

    protected MutableClock clock;
    protected RateLimiter limiter;

    protected abstract RateLimiter newLimiter(Clock clock);

    @BeforeEach
    protected void setUpTck() {
        clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
        limiter = newLimiter(clock);
    }

    @Test
    protected void rejectsBeyondLimit() {
        assertTrue(limiter.tryAcquire("t1", "api:order", 3, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("t1", "api:order", 3, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("t1", "api:order", 3, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("t1", "api:order", 3, Duration.ofMinutes(1)));
    }

    @Test
    protected void newWindowAllowsAgain() {
        assertTrue(limiter.tryAcquire("t1", "api:pay", 1, Duration.ofSeconds(30)));
        assertFalse(limiter.tryAcquire("t1", "api:pay", 1, Duration.ofSeconds(30)));
        clock.advance(Duration.ofSeconds(31));
        assertTrue(limiter.tryAcquire("t1", "api:pay", 1, Duration.ofSeconds(30)));
    }

    @Test
    protected void dimensionsAreIsolated() {
        assertTrue(limiter.tryAcquire("t1", "api:a", 1, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("t1", "api:a", 1, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("t1", "api:b", 1, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("t2", "api:a", 1, Duration.ofMinutes(1)));
    }
}
