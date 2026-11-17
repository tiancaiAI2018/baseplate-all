package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.contract.tck.RateLimiterTck;

class InMemoryRateLimiterTckTest extends RateLimiterTck {
    @Override
    protected RateLimiter newLimiter(Clock clock) {
        return new InMemoryRateLimiter(clock);
    }
}
