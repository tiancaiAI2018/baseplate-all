package com.example.baseplate.api.model;

import java.time.Instant;

/** 待办任务视图：不泄漏引擎内部对象。带流程/单号/发起人，待办列表不用再逐条查实例。 */
public record TaskView(String taskId, String name, String assignee,
                       String processInstanceId, String processKey, String processName,
                       String businessKey, String activityId, String startUserId,
                       String startUserName, Instant processStartedAt, Instant createdAt) {
}