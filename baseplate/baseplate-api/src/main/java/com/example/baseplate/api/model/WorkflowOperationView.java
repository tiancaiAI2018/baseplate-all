package com.example.baseplate.api.model;

import java.time.Instant;

/** 操作流水的一行：谁、什么时候、因为什么、对哪个节点做了什么。 */
public record WorkflowOperationView(
        long id,
        String action,
        String decision,
        String reason,
        String operator,
        String activityId,
        String targetNodeId,
        String taskId,
        Instant occurredAt) {
}
