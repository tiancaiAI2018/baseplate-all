package com.example.baseplate.api.model;

import java.util.Set;

/** 数据范围：作为领域语义的查询条件下传，落地由持久化适配器负责。 */
public record DataScope(ScopeKind kind, Set<Long> orgIds, Set<String> userIds, String tenant) {

    public enum ScopeKind {
        ALL,
        ORG_AND_CHILDREN,
        ORG,
        SELF,
        CUSTOM
    }
}
