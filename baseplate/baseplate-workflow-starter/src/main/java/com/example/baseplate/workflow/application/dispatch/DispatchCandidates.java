package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.model.DispatchCandidate;

import java.time.Instant;
import java.util.List;

/** 候选人快照的构造入口，避免在多个服务里重复拼装。 */
public final class DispatchCandidates {

    private DispatchCandidates() {
    }

    public static DispatchCandidate of(String userId, String displayName, Long orgId,
                                       boolean checkedIn, int activeCount, int capacity,
                                       double weight, double rating, int ratingCount,
                                       int recentAssigned, List<String> skills) {
        return new DispatchCandidate(userId, displayName, orgId, checkedIn, "OFFLINE", null,
                activeCount, capacity, weight, rating, ratingCount, 0, null,
                skills == null ? List.of() : skills, recentAssigned);
    }

    public static boolean cooled(DispatchCandidate candidate, Instant now) {
        return candidate.cooldownUntil() != null && candidate.cooldownUntil().isAfter(now);
    }
}
