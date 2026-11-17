package com.example.baseplate.api.model;

/** 端口能力声明：弱实现必须显式标注，不允许默默降级。 */
public enum Capability {
    ATOMIC_SINGLE_KEY,
    DISTRIBUTED,
    TTL,
    ORDERED,
    AT_LEAST_ONCE,
    TRANSACTIONAL_ENLIST,
    TENANT_AWARE
}
