package com.example.baseplate.api.model;

import java.time.Instant;

/** 任务轨迹的一行：已结束任务也必须能查到，所以用引擎历史表而不是运行表。 */
public record WorkflowTaskTrace(
        String taskId,
        String name,
        String assignee,
        String activityId,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String decision,
        String reason,
        String deleteReason,
        boolean ended) {
}
