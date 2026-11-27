package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型调用观测持久化：每行一次发给模型的 HTTP 请求体（OpenAI 风格）。
 *
 * <p>请求体是累积的：第 N 次调用包含前 N-1 次的全部 messages，
 * 单会话全量 request 的体积随轮次近似平方增长（30 轮即可超过 1MB）。
 * 观测详情与导出只取「消息最多的一份」，不要按会话全量读取。</p>
 */
public final class AgentModelCallRepository {

    private final JdbcTemplate jdbc;

    public AgentModelCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String tenantId, long sessionId, int step, String model, String requestJson) {
        jdbc.update("""
                INSERT INTO agent_model_call (tenant_id, session_id, step, model, request)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, tenantId, sessionId, step, model, requestJson);
    }

    /** 观测详情只展示调用次数，不传请求体。 */
    public int countBySession(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_model_call WHERE session_id = ?",
                Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    /**
     * 消息数最多的那次请求体：完整对话都在这份里，解析一份即可。
     * 同消息数时取最新一次，保证工具与采样参数尽量新。
     */
    public Optional<String> widestRequest(long sessionId) {
        return jdbc.query("""
                SELECT request::text AS request_json
                  FROM agent_model_call
                 WHERE session_id = ?
                 ORDER BY jsonb_array_length(request->'messages') DESC, id DESC
                 LIMIT 1
                """, (rs, i) -> rs.getString(1), sessionId).stream().findFirst();
    }

    /** 最近一次非空 tools 定义：工具是固定前缀，取最新一份即可。 */
    public Optional<String> latestTools(long sessionId) {
        return jdbc.query("""
                SELECT (request->'tools')::text AS tools
                  FROM agent_model_call
                 WHERE session_id = ? AND jsonb_array_length(request->'tools') > 0
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, i) -> rs.getString(1), sessionId).stream().findFirst();
    }

    /** 批量版：导出时一次取多会话的最宽请求，避免逐会话往返。 */
    public Map<Long, String> widestRequests(List<Long> sessionIds) {
        return queryPayloadBySessions(sessionIds, """
                SELECT DISTINCT ON (session_id) session_id, request::text AS payload
                  FROM agent_model_call
                 WHERE session_id IN (%s)
                 ORDER BY session_id, jsonb_array_length(request->'messages') DESC, id DESC
                """);
    }

    /** 批量版：每个会话最近一次非空 tools 定义。 */
    public Map<Long, String> latestToolsBySession(List<Long> sessionIds) {
        return queryPayloadBySessions(sessionIds, """
                SELECT DISTINCT ON (session_id) session_id, (request->'tools')::text AS payload
                  FROM agent_model_call
                 WHERE session_id IN (%s) AND jsonb_array_length(request->'tools') > 0
                 ORDER BY session_id, id DESC
                """);
    }

    private Map<Long, String> queryPayloadBySessions(List<Long> sessionIds, String sqlTemplate) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        List<Map.Entry<Long, String>> rows = jdbc.query(sqlTemplate.formatted(placeholders),
                (rs, i) -> Map.entry(rs.getLong("session_id"), rs.getString("payload")),
                sessionIds.toArray());
        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<Long, String> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
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
