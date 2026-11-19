package com.example.baseplate.agent.domain;

import java.time.OffsetDateTime;

/** 会话摘要：列表页与「继续对话」使用，正文在 agent_message。 */
public record AgentSession(
        long id,
        String tenantId,
        long agentId,
        String userId,
        String pageKey,
        String title,
        int messageCount,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt) {
}
