package com.example.baseplate.runtime.tenant;

import com.example.baseplate.api.model.TenantId;

import java.util.Optional;

/**
 * 租户上下文：只允许从认证链写入，禁止从请求参数取。
 * 显式传递优先；ThreadLocal 仅作为适配层便利，异步/虚拟线程必须用 withTenant 包住。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantId tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<TenantId> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantId require() {
        TenantId tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("缺少 TenantContext：禁止从请求参数取租户");
        }
        return tenantId;
    }

    public static <T> T withTenant(TenantId tenantId, java.util.function.Supplier<T> body) {
        TenantId previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
