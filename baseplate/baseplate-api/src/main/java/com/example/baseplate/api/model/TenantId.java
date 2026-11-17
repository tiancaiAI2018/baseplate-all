package com.example.baseplate.api.model;

import java.util.Objects;

/** 租户标识：只能来自认证上下文，禁止从请求参数传入。 */
public record TenantId(String value) {
    public TenantId {
        Objects.requireNonNull(value, "tenantId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
    }
}
