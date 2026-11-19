package com.example.baseplate.api.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 节点的结构化配置：页面只下拉/勾选，不产生任何表达式或脚本。
 * 部署时由 NodeConfigCompiler 编译成 Flowable 可执行的 BPMN 片段。
 *
 * 多表单：formCodes 是本「节点新增」的表单编码；节点实际可用的表单 =
 * 流程级基础表单（wf_process_setting.base_form_codes）∪ formCodes。
 * fieldPolicy 是二维结构：{formCode: {field: EDITABLE/READONLY/HIDDEN}}。
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
        List<String> formCodes,
        Map<String, Map<String, Object>> fieldPolicy,
        Map<String, Object> decisionConfig,
        Map<String, Object> triggerConfig,
        Map<String, Object> conditionConfig,
        Integer timeoutMinutes) {

    @SuppressWarnings("unchecked")
    public WorkflowNodeConfig {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("节点 ID 不能为空");
        }
        assigneeType = assigneeType == null || assigneeType.isBlank() ? "INITIATOR" : assigneeType;
        signType = signType == null || signType.isBlank() ? "SINGLE" : signType;
        signRatio = signRatio == null || signRatio <= 0 ? 100 : Math.min(100, signRatio);
        timeoutMinutes = timeoutMinutes == null || timeoutMinutes < 0 ? 0 : timeoutMinutes;
        formCodes = normalizeFormCodes(formCodes);
        fieldPolicy = normalizeFieldPolicy(fieldPolicy);
        decisionConfig = decisionConfig == null ? Map.of() : decisionConfig;
        triggerConfig = triggerConfig == null ? Map.of() : triggerConfig;
        conditionConfig = conditionConfig == null ? Map.of() : conditionConfig;
    }

    private static List<String> normalizeFormCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String code : codes) {
            if (code != null && !code.isBlank() && !result.contains(code)) {
                result.add(code.trim());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 归一化二维权限：剔除空 formCode / 空 map。AI 草稿偶尔会把权限写成一维
     * （直接 field→policy），这里不猜测、不兼容——开发期数据可放弃，非法结构就当空。
     */
    private static Map<String, Map<String, Object>> normalizeFieldPolicy(
            Map<String, Map<String, Object>> policy) {
        if (policy == null || policy.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : policy.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                result.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
        }
        return Map.copyOf(result);
    }

    public String collectionVariable() {
        return "wf_assignees_" + nodeId.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public String assigneeVariable() {
        return "wf_assignee_" + nodeId.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
