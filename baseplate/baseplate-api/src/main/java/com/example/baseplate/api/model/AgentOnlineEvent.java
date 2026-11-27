package com.example.baseplate.api.model;

import java.time.Instant;

/**
 * 坐席上线信号：签到成功后、在当前事务提交时发布。
 *
 * <p>workflow 订阅它做「待派任务实时重派」——解决「发起时全员未签到、
 * 后来有人上线」却要等 30 分钟超时扫描才重派的问题。业务方也可订阅
 * 本事件做通知、排班统计等扩展。
 *
 * <p>纯 record、零框架依赖，符合 baseplate-api 边界。
 */
public record AgentOnlineEvent(
        String tenantId,
        String userId,
        String source,
        Instant occurredAt) {

    public AgentOnlineEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
