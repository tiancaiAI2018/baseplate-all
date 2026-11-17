package com.example.baseplate.contract.fixture;

import com.example.baseplate.api.port.out.Clock;

import java.time.Duration;
import java.time.Instant;

/** 可控时钟：让 TTL 语义可判定，不靠 sleep。 */
public final class MutableClock implements Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    @Override
    public Instant now() {
        return now;
    }

    public void advance(Duration duration) {
        this.now = this.now.plus(duration);
    }
}
