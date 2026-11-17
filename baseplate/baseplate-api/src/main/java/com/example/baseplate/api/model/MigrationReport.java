package com.example.baseplate.api.model;

import java.util.List;

/** 迁移预检 / 执行报告：整体拒绝策略下，issues 非空就不允许执行。 */
public record MigrationReport(
        boolean canMigrate,
        int affectedInstances,
        String fromDefinitionId,
        String toDefinitionId,
        List<String> issues) {
}
