package com.example.baseplate.workflow.application.dispatch;

import java.util.Map;

/** 节点分单策略。config 是 policy_json，键名见 DispatchCenterView 的策略表单。 */
public record DispatchPolicy(
        Long id,
        Long poolId,
        String mode,
        String algorithm,
        Map<String, Object> config) {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_CLAIM = "CLAIM";
    public static final String MODE_MIXED = "MIXED";

    public DispatchPolicy {
        mode = mode == null || mode.isBlank() ? MODE_AUTO : mode.toUpperCase();
        algorithm = algorithm == null || algorithm.isBlank() ? "LEAST_LOAD" : algorithm.toUpperCase();
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean claimEnabled() {
        return MODE_CLAIM.equals(mode) || MODE_MIXED.equals(mode);
    }

    public boolean autoEnabled() {
        return MODE_AUTO.equals(mode) || MODE_MIXED.equals(mode);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String key) {
        Object value = config.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public boolean flag(String key, boolean fallback) {
        Object value = config.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    public int integer(String key, int fallback) {
        Object value = config.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
