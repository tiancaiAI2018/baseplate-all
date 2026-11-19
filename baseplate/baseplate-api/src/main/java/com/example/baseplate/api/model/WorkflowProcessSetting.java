package com.example.baseplate.api.model;

import java.util.List;

/**
 * 流程发起配置（按租户 + 流程 Key）：分类/图标/描述用于「发起流程」目录，
 * startScope 控制谁能发起（数据权限的发起侧），enabled=false 时目录隐藏且拒绝发起。
 *
 * baseFormCodes 是流程级基础表单：所有节点自动继承，节点只能新增、不能删除。
 */
public record WorkflowProcessSetting(String processKey, String category, String description,
                                     String icon, String startScopeType, String startScopeValue,
                                     List<String> baseFormCodes, boolean enabled, int sortOrder) {

    public WorkflowProcessSetting {
        baseFormCodes = baseFormCodes == null ? List.of() : List.copyOf(baseFormCodes);
    }

    public static WorkflowProcessSetting defaults(String processKey) {
        return new WorkflowProcessSetting(processKey, "通用", "", "", "ALL", "", List.of(), true, 0);
    }
}
