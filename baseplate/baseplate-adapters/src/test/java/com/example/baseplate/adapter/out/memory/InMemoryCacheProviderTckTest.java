package com.example.baseplate.adapter.out.memory;

import com.example.baseplate.api.port.out.CacheProvider;
import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.contract.tck.CacheProviderTck;

class InMemoryCacheProviderTckTest extends CacheProviderTck {
    @Override
    protected CacheProvider newCache(Clock clock) {
        return new InMemoryCacheProvider(clock);
    }
}
