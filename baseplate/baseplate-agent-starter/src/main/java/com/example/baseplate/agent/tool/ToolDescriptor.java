package com.example.baseplate.agent.tool;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;

import java.util.Map;
import java.util.Set;

/**
 * 工具在代码里的元数据。内置工具的 name/description/pages 写死在代码里，
 * 数据库只同步一份只读目录，运营只能启停与配置审批策略。
 */
public record ToolDescriptor(
        String code,
        String name,
        String description,
        ToolType type,
        boolean builtin,
        boolean editable,
        Set<String> pages,
        String permissionCode,
        RiskLevel risk,
        ApprovalMode minApproval,
        ApprovalMode defaultApproval,
        Map<String, Object> paramsSchema) {

    /** 兼容普通内置工具：没有参数 schema。 */
    public ToolDescriptor(String code, String name, String description, ToolType type,
                          boolean builtin, boolean editable, Set<String> pages,
                          String permissionCode, RiskLevel risk, ApprovalMode minApproval,
                          ApprovalMode defaultApproval) {
        this(code, name, description, type, builtin, editable, pages, permissionCode,
                risk, minApproval, defaultApproval, Map.of());
    }

    public boolean allowedOnPage(String pageKey) {
        return pages == null || pages.isEmpty() || pages.contains("*") || pages.contains(pageKey);
    }
}
