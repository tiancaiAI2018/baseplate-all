package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

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

    /** 观测页展示用：不落库，只读。 */
    public record RunRow(
            long id,
            Long messageId,
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
            String errorMessage,
            OffsetDateTime createdAt) {
    }

    private static final RowMapper<RunRow> ROW_MAPPER = (rs, i) -> new RunRow(
            rs.getLong("id"),
            rs.getObject("message_id") == null ? null : rs.getLong("message_id"),
            rs.getString("model"),
            rs.getInt("input_tokens"),
            rs.getInt("output_tokens"),
            rs.getInt("cached_tokens"),
            rs.getInt("steps"),
            rs.getInt("tool_calls"),
            rs.getInt("latency_ms"),
            rs.getInt("ttft_ms"),
            rs.getString("status"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getObject("created_at", OffsetDateTime.class));

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

    /** 会话详情里的逐轮指标，按时间倒序。 */
    public List<RunRow> listBySession(long sessionId) {
        return jdbc.query("""
                SELECT * FROM agent_run
                 WHERE session_id = ?
                 ORDER BY created_at DESC, id DESC
                """, ROW_MAPPER, sessionId);
    }
}
