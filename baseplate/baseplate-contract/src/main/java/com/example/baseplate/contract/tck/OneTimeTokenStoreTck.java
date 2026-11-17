package com.example.baseplate.contract.tck;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import com.example.baseplate.contract.fixture.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-ONETIME-01..03：一次性、用途域绑定、TTL。 */
public abstract class OneTimeTokenStoreTck {

    protected MutableClock clock;
    protected OneTimeTokenStore tokenStore;

    protected abstract OneTimeTokenStore newStore(Clock clock, IdGenerator ids);

    @BeforeEach
    protected void setUpTck() {
        clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
        AtomicInteger seq = new AtomicInteger();
        tokenStore = newStore(clock, () -> "jti-" + seq.incrementAndGet());
    }

    @Test
    protected void consumeExactlyOnce() {
        String jti = tokenStore.issue("RESET_PASSWORD", "user-1", Duration.ofMinutes(15));
        assertEquals("user-1", tokenStore.consume("RESET_PASSWORD", jti).orElseThrow());
        assertTrue(tokenStore.consume("RESET_PASSWORD", jti).isEmpty());
    }

    @Test
    protected void wrongPurposeIsRejected() {
        String jti = tokenStore.issue("RESET_PASSWORD", "user-1", Duration.ofMinutes(15));
        assertTrue(tokenStore.consume("ACTIVATE_ACCOUNT", jti).isEmpty());
        // 用途不匹配时不应消费掉，原用途仍可用
        assertEquals("user-1", tokenStore.consume("RESET_PASSWORD", jti).orElseThrow());
    }

    @Test
    protected void expiredTokenIsRejected() {
        String jti = tokenStore.issue("DOWNLOAD", "file-9", Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(11));
        assertTrue(tokenStore.consume("DOWNLOAD", jti).isEmpty());
    }
}
