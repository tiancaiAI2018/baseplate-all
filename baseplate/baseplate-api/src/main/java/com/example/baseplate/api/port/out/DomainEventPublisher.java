package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.DomainEvent;

/**
 * 领域事件：至少一次投递。
 * 事务内只登记 Outbox，禁止直接外发；消费端必须幂等。
 */
public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
