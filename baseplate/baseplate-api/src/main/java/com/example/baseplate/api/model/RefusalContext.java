package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.Map;

/** 拒绝求值上下文：策略处理器据此决定冷却、降权还是仅排除本单。 */
public record RefusalContext(
        String tenantId,
        Long poolId,
        String poolCode,
        String taskId,
        String processInstanceId,
        String userId,
        String refusalType,
        String reasonCode,
        String reasonText,
        int windowRefusalCount,
        int consecutiveRefusals,
        Map<String, Object> policy,
        Instant now) {

    public RefusalContext {
        policy = policy == null ? Map.of() : Map.copyOf(policy);
        now = now == null ? Instant.now() : now;
    }
}
