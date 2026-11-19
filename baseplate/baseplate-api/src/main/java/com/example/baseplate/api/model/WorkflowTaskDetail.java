package com.example.baseplate.api.model;

import java.util.List;
import java.util.Map;

/**
 * 任务办理详情：一次拿齐表单 schema、已填数据、节点决策按钮与可回退节点，
 * 普通用户（只有 wf:task）也能渲染办理页，不依赖管理侧的 meta:view / wf:view。
 *
 * formDefinitions 是节点实际可用的全部表单（流程基础表单 ∪ 节点新增），
 * 每份表单自带 schema/uiSchema 和已按当前节点权限裁剪后的数据。
 */
public record WorkflowTaskDetail(TaskView task, WorkflowNodeConfig nodeConfig,
                                 List<FormView> formDefinitions, boolean canHandle, boolean ended,
                                 List<String> rollbackTargets) {

    /** 单个表单在办理页的完整视图。 */
    public record FormView(String formCode, String formName, String formSchema, String formUiSchema,
                           Map<String, Object> formData, boolean base) {
    }
}
