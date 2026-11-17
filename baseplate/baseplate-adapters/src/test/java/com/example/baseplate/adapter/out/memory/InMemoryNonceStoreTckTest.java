package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.NonceStore;
import com.example.baseplate.contract.tck.NonceStoreTck;

class InMemoryNonceStoreTckTest extends NonceStoreTck {
    @Override
    protected NonceStore newStore(Clock clock) {
        return new InMemoryNonceStore(clock);
    }
}
