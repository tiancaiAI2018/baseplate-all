package com.example.baseplate.contract.tck;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.NonceStore;
import com.example.baseplate.contract.fixture.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-NONCE-01..04。 */
public abstract class NonceStoreTck {

    protected MutableClock clock;
    protected NonceStore nonceStore;

    protected abstract NonceStore newStore(Clock clock);

    @BeforeEach
    protected void setUpTck() {
        clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
        nonceStore = newStore(clock);
    }

    @Test
    protected void firstConsumeWinsSecondLoses() {
        assertTrue(nonceStore.tryConsume("t1", "app-a", "n-1", Duration.ofMinutes(5)));
        assertFalse(nonceStore.tryConsume("t1", "app-a", "n-1", Duration.ofMinutes(5)));
    }

    @Test
    protected void differentAppKeyDoesNotCollide() {
        assertTrue(nonceStore.tryConsume("t1", "app-a", "n-2", Duration.ofMinutes(5)));
        assertTrue(nonceStore.tryConsume("t1", "app-b", "n-2", Duration.ofMinutes(5)));
    }

    @Test
    protected void differentTenantDoesNotCollide() {
        assertTrue(nonceStore.tryConsume("t1", "app-a", "n-3", Duration.ofMinutes(5)));
        assertTrue(nonceStore.tryConsume("t2", "app-a", "n-3", Duration.ofMinutes(5)));
    }

    @Test
    protected void expiredNonceCanBeReused() {
        assertTrue(nonceStore.tryConsume("t1", "app-a", "n-4", Duration.ofSeconds(30)));
        clock.advance(Duration.ofSeconds(31));
        assertTrue(nonceStore.tryConsume("t1", "app-a", "n-4", Duration.ofSeconds(30)));
    }
}
