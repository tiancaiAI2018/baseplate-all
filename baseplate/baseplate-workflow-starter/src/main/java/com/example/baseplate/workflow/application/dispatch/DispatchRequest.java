package com.example.baseplate.workflow.application.dispatch;

import java.util.Map;

/** 一次分单请求。variables 是流程变量快照，requesterId 来自发起人。 */
public record DispatchRequest(
        String tenantId,
        String processKey,
        int definitionVersion,
        String nodeId,
        String processInstanceId,
        String taskId,
        Map<String, Object> variables,
        String requesterId) {

    public DispatchRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
