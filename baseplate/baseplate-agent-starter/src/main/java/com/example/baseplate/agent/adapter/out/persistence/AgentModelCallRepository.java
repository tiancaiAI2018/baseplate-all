package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 模型调用观测持久化：每行一次发给模型的 HTTP 请求体（OpenAI 风格）。
 *
 * <p>请求体在 middleware 的 onModelCall 拦截点生成，是「实际发送给模型接口」的
 * 最准确快照：messages 含 skill 注入后的 system 消息，tools 含全部下发工具。</p>
 */
public final class AgentModelCallRepository {

    private final JdbcTemplate jdbc;

    public AgentModelCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ModelCallRow(long id, int step, String model, String requestJson) {
    }

    public void record(String tenantId, long sessionId, int step, String model, String requestJson) {
        jdbc.update("""
                INSERT INTO agent_model_call (tenant_id, session_id, step, model, request)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, tenantId, sessionId, step, model, requestJson);
    }

    /** 会话详情/导出用：按调用顺序返回原始请求体 JSON。 */
    public List<ModelCallRow> listBySession(long sessionId) {
        return jdbc.query("""
                SELECT id, step, model, request::text AS request_json
                  FROM agent_model_call
                 WHERE session_id = ?
                 ORDER BY id
                """, (rs, i) -> new ModelCallRow(rs.getLong("id"), rs.getInt("step"),
                rs.getString("model"), rs.getString("request_json")), sessionId);
    }

    /**
     * 最近一次调用请求体里的 system 消息内容。skill 正文在每次调用前动态注入，
     * 所以这是唯一准确的「实际发送的 system prompt」来源。
     */
    public Optional<String> latestSystemPrompt(long sessionId) {
        return jdbc.query("""
                SELECT request->'messages'->0->>'content' AS content
                  FROM agent_model_call
                 WHERE session_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, i) -> rs.getString(1), sessionId).stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** 会话删除由 agent_session 级联完成；这里提供按会话清理的显式入口。 */
    public void deleteBySession(long sessionId) {
        jdbc.update("DELETE FROM agent_model_call WHERE session_id = ?", sessionId);
    }
}
