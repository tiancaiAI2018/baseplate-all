package com.example.baseplate.api.model;

import java.time.Instant;

/** 已办任务视图：我办理过的历史任务，带审批结论与意见。 */
public record WorkflowDoneTaskView(String taskId, String name, String processInstanceId,
                                   String processKey, String processName, String businessKey,
                                   String activityId, String startUserId, String startUserName,
                                   Instant startedAt, Instant endedAt, Long durationMs,
                                   String decision, String reason) {
}