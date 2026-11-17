package com.example.baseplate.api.model;

import java.time.Instant;
import java.util.List;

/** 流程定义详情：版本列表 + 实例统计 + 图形能力，供定义详情页使用。 */
public record WorkflowDefinitionDetail(
        String id,
        String key,
        String name,
        int version,
        String deploymentId,
        Instant deployedAt,
        boolean suspended,
        boolean hasDiagram,
        String tenantId,
        int runningInstances,
        int finishedInstances,
        List<ProcessDefinitionView> versions,
        List<String> activityIds) {
}
