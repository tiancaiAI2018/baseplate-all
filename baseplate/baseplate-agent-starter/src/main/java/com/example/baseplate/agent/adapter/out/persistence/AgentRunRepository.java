package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 运行观测：每轮对话一行，记录 token、耗时与错误。
 * 先落库，后续接 Micrometer 时同一份数据直接用。
 */
public final class AgentRunRepository {

    private final JdbcTemplate jdbc;

    public AgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RunRecord(
            String tenantId,
            long sessionId,
            Long messageId,
            String traceId,
            String model,
            int inputTokens,
            int outputTokens,
            int cachedTokens,
            int steps,
            int toolCalls,
            int latencyMs,
            int ttftMs,
            String status,
            String errorCode,
            String errorMessage) {
    }

    public void record(RunRecord run) {
        jdbc.update("""
                INSERT INTO agent_run
                    (tenant_id, session_id, message_id, trace_id, model,
                     input_tokens, output_tokens, cached_tokens, steps, tool_calls,
                     latency_ms, ttft_ms, status, error_code, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.tenantId(), run.sessionId(), run.messageId(), run.traceId(), run.model(),
                run.inputTokens(), run.outputTokens(), run.cachedTokens(), run.steps(), run.toolCalls(),
                run.latencyMs(), run.ttftMs(), run.status(), run.errorCode(), run.errorMessage());
    }
}
