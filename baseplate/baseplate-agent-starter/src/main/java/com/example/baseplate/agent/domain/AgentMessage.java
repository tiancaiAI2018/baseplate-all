package com.example.baseplate.agent.domain;

import java.time.OffsetDateTime;

/**
 * 一条对话消息。{@code blocksJson} 是按顺序排列的事件块数组：
 * text / thinking / tool_call / tool_result / plan / skill / hitl。
 *
 * <p>回放时不重放 token，直接按块渲染，刷新页面后仍能看到完整过程。</p>
 */
public record AgentMessage(
        long id,
        long sessionId,
        int seq,
        String role,
        String content,
        String blocksJson,
        String model,
        int latencyMs,
        OffsetDateTime createdAt) {
}
