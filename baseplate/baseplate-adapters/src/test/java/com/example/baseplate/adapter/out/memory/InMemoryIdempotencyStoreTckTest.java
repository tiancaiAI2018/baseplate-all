package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdempotencyStore;
import com.example.baseplate.contract.tck.IdempotencyStoreTck;

class InMemoryIdempotencyStoreTckTest extends IdempotencyStoreTck {
    @Override
    protected IdempotencyStore newStore(Clock clock) {
        return new InMemoryIdempotencyStore(clock);
    }
}
