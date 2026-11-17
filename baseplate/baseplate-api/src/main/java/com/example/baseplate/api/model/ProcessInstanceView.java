package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.List;

/** 流程实例视图：当前停在哪几个任务、是否结束，一眼能看出来。 */
public record ProcessInstanceView(
        String id,
        String processDefinitionKey,
        String processDefinitionName,
        String businessKey,
        String startUserId,
        Instant startedAt,
        Instant endedAt,
        boolean ended,
        List<String> currentTasks,
        String processDefinitionId,
        int version,
        int rollbackCount,
        String deleteReason) {

    public ProcessInstanceView(String id, String processDefinitionKey, String processDefinitionName,
                               String businessKey, String startUserId, Instant startedAt,
                               Instant endedAt, boolean ended, List<String> currentTasks) {
        this(id, processDefinitionKey, processDefinitionName, businessKey, startUserId,
                startedAt, endedAt, ended, currentTasks, null, 0, 0, null);
    }
}
