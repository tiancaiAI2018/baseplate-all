package com.example.baseplate.api.model;

import java.util.Set;

/** 当前用户快照：由认证上下文构造，不携带口令等敏感信息。 */
public record CurrentUser(
        String userId,
        String tenantId,
        String username,
        Set<String> roles,
        Set<String> permissions,
        boolean mustChangePassword) {
}
