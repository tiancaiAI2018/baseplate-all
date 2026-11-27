package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.Map;

/**
 * 分单求值上下文。所有扩展点共用，故意不带 Flowable / Spring 类型。
 * policy 是节点策略 JSON 的快照，过滤器据此决定签到、在线等开关。
 */
public record DispatchContext(
        String tenantId,
        String processKey,
        String nodeId,
        String processInstanceId,
        String taskId,
        Long poolId,
        String poolCode,
        Map<String, Object> variables,
        Map<String, Object> policy,
        Instant now) {

    public DispatchContext {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        policy = policy == null ? Map.of() : Map.copyOf(policy);
        now = now == null ? Instant.now() : now;
    }

    public boolean flag(String key, boolean fallback) {
        Object value = policy.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    public int integer(String key, int fallback) {
        Object value = policy.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
