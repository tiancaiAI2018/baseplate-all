package com.example.baseplate.api.model;

import java.util.Map;

/**
 * 节点的结构化配置：页面只下拉/勾选，不产生任何表达式或脚本。
 * 部署时由 NodeConfigCompiler 编译成 Flowable 可执行的 BPMN 片段。
 */
public record WorkflowNodeConfig(
        String nodeId,
        String nodeName,
        String nodeType,
        String assigneeType,
        String assigneeValue,
        String signType,
        /**
         * 用包装类型而不是 int：Jackson 3 默认对「显式 null → 基本类型」直接报 400，
         * 而 AI 生成的节点配置里这些字段经常是 null。构造器里统一兜底。
         */
        Integer signRatio,
        String formCode,
        Map<String, Object> fieldPolicy,
        Map<String, Object> decisionConfig,
        Map<String, Object> triggerConfig,
        Map<String, Object> conditionConfig,
        Integer timeoutMinutes) {

    public WorkflowNodeConfig {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("节点 ID 不能为空");
        }
        assigneeType = assigneeType == null || assigneeType.isBlank() ? "INITIATOR" : assigneeType;
        signType = signType == null || signType.isBlank() ? "SINGLE" : signType;
        signRatio = signRatio == null || signRatio <= 0 ? 100 : Math.min(100, signRatio);
        timeoutMinutes = timeoutMinutes == null || timeoutMinutes < 0 ? 0 : timeoutMinutes;
        fieldPolicy = fieldPolicy == null ? Map.of() : fieldPolicy;
        decisionConfig = decisionConfig == null ? Map.of() : decisionConfig;
        triggerConfig = triggerConfig == null ? Map.of() : triggerConfig;
        conditionConfig = conditionConfig == null ? Map.of() : conditionConfig;
    }

    public String collectionVariable() {
        return "wf_assignees_" + nodeId.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public String assigneeVariable() {
        return "wf_assignee_" + nodeId.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
