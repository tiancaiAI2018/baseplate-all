package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import com.example.baseplate.contract.tck.OneTimeTokenStoreTck;

class InMemoryOneTimeTokenStoreTckTest extends OneTimeTokenStoreTck {
    @Override
    protected OneTimeTokenStore newStore(Clock clock, IdGenerator ids) {
        return new InMemoryOneTimeTokenStore(clock, ids);
    }
}
