package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.KeyValueStore;
import com.example.baseplate.contract.tck.KeyValueStoreTck;

class InMemoryKeyValueStoreTckTest extends KeyValueStoreTck {
    @Override
    protected KeyValueStore newStore(Clock clock) {
        return new InMemoryKeyValueStore(clock);
    }
}
