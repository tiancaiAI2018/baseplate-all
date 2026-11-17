package com.example.baseplate.api.model;

import java.util.List;

/**
 * 模块清单解析结果（对应 META-INF/baseplate-module.yml）。
 * permissions 保留编码列表用于兼容；permissionDefs 带中文标签与类型，供权限配置页使用。
 */
public record ModuleDescriptor(
        String id,
        String displayName,
        String version,
        String requires,
        List<String> dependsOn,
        String rootPackage,
        boolean enabledByDefault,
        List<String> permissions,
        List<String> flywayLocations,
        String openapiGroup,
        List<MenuDef> menus,
        List<RuleFieldDef> ruleFields,
        List<PermissionDef> permissionDefs) {
}
