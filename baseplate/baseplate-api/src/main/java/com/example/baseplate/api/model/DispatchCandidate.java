package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.List;

/**
 * 分单候选人快照：状态在派单那一刻已经查好，打分器只做纯计算。
 * 放在内核契约里，扩展点不需要依赖 workflow 模块。
 */
public record DispatchCandidate(
        String userId,
        String displayName,
        Long orgId,
        boolean checkedIn,
        String onlineStatus,
        Instant lastHeartbeat,
        int activeCount,
        int capacity,
        double weight,
        double rating,
        int ratingCount,
        int consecutiveRefusals,
        Instant cooldownUntil,
        List<String> skills,
        int recentAssigned) {

    public DispatchCandidate {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public boolean capacityLimited() {
        return capacity > 0;
    }

    public double idleRatio() {
        if (!capacityLimited()) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - (double) activeCount / capacity);
    }

    public boolean cooling(Instant now) {
        Instant at = now == null ? Instant.now() : now;
        return cooldownUntil != null && cooldownUntil.isAfter(at);
    }
}
