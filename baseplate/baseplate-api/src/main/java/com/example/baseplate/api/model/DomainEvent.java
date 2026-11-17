package com.example.baseplate.api.model;

/** 领域事件标记：实现方提供类型与聚合标识，payload 由序列化层处理。 */
public interface DomainEvent {
    String eventType();

    String aggregateId();
}
