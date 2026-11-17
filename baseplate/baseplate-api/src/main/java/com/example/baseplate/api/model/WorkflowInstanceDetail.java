package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 实例详情：运行中与已结束实例共用同一个结构。
 * 图高亮用的三组节点 ID、原因与打回次数都在这里，前端不需要自己拼。
 */
public record WorkflowInstanceDetail(
        String id,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version,
        String businessKey,
        String startUserId,
        Instant startedAt,
        Instant endedAt,
        boolean ended,
        String deleteReason,
        Long durationMs,
        List<String> currentActivities,
        List<String> completedActivities,
        List<String> rolledBackActivities,
        int rollbackCount,
        String lastReason,
        String lastDecision,
        List<WorkflowTaskTrace> tasks,
        Map<String, Object> variables,
        List<WorkflowOperationView> operations) {
}
