package com.example.baseplate.api.model;

import java.util.List;
import java.util.Map;

/**
 * 任务办理详情：一次拿齐表单 schema、已填数据、节点决策按钮与可回退节点，
 * 普通用户（只有 wf:task）也能渲染办理页，不依赖管理侧的 meta:view / wf:view。
 */
public record WorkflowTaskDetail(TaskView task, WorkflowNodeConfig nodeConfig,
                                 String formCode, String formSchema, String formUiSchema,
                                 Map<String, Object> formData, boolean canHandle, boolean ended,
                                 List<String> rollbackTargets) {
}