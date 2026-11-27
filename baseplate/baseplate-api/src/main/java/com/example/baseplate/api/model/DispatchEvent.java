package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.Map;

/**
 * 分单生命周期事件：业务方通过 DispatchLifecycleListener 订阅，
 * 用来发通知、写绩效、接结算，不改核心分单逻辑。
 */
public record DispatchEvent(
        Type type,
        String tenantId,
        String processInstanceId,
        String taskId,
        String nodeId,
        Long poolId,
        String userId,
        String reason,
        Instant occurredAt,
        Map<String, Object> payload) {

    public enum Type {
        ASSIGNED,
        POOLED,
        CLAIMED,
        REFUSED,
        REQUEUED,
        TIMEOUT,
        FAILED
    }

    public DispatchEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
