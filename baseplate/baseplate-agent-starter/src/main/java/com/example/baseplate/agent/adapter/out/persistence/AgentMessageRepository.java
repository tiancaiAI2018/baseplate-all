package com.example.baseplate.agent.adapter.out.persistence;

import com.example.baseplate.agent.domain.AgentMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 消息持久化：seq 在每个会话内单调递增，前端按 seq 排序去重。 */
public final class AgentMessageRepository {

    private final JdbcTemplate jdbc;

    public AgentMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AgentMessage> MAPPER = (rs, i) -> new AgentMessage(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("blocks"),
            rs.getString("model"),
            rs.getInt("latency_ms"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("feedback"));

    public long append(String tenant, long sessionId, String role, String content,
                       String blocksJson, String model, int latencyMs) {
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_message (tenant_id, session_id, seq, role, content, blocks, model, latency_ms)
                SELECT ?, ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?::jsonb, ?, ?
                  FROM agent_message
                 WHERE session_id = ?
                RETURNING id
                """, Long.class, tenant, sessionId, role, content, blocksJson, model, latencyMs, sessionId);
        return id == null ? 0L : id;
    }

    public List<AgentMessage> list(long sessionId) {
        return jdbc.query("""
                SELECT * FROM agent_message
                 WHERE session_id = ?
                 ORDER BY seq
                """, MAPPER, sessionId);
    }

    /** 最后一条非空助手回复：不在任何请求体里，观测/导出需要单独补到合并请求末尾。 */
    public Optional<String> latestAssistantContent(long sessionId) {
        return jdbc.query("""
                SELECT content FROM agent_message
                 WHERE session_id = ? AND role = 'ASSISTANT'
                   AND content IS NOT NULL AND content <> ''
                 ORDER BY seq DESC
                 LIMIT 1
                """, (rs, i) -> rs.getString(1), sessionId).stream().findFirst();
    }

    /** 批量版：导出时一次取多会话的最后一条助手回复。 */
    public Map<Long, String> latestAssistantContents(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        List<Map.Entry<Long, String>> rows = jdbc.query("""
                SELECT DISTINCT ON (session_id) session_id, content AS payload
                  FROM agent_message
                 WHERE session_id IN (%s) AND role = 'ASSISTANT'
                   AND content IS NOT NULL AND content <> ''
                 ORDER BY session_id, seq DESC
                """.formatted(placeholders),
                (rs, i) -> Map.entry(rs.getLong("session_id"), rs.getString("payload")),
                sessionIds.toArray());
        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<Long, String> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    /**
     * 给自己的助手回复打分；rating 为 null 表示清除评分。
     * 通过 join agent_session 限制只能评自己会话里的 ASSISTANT 消息，返回是否真的更新了。
     */
    public boolean setFeedback(String tenant, String userId, long messageId, String rating) {
        return jdbc.update("""
                UPDATE agent_message m
                   SET feedback = ?, feedback_at = CASE WHEN ?::varchar IS NULL THEN NULL ELSE now() END
                  FROM agent_session s
                 WHERE m.session_id = s.id
                   AND m.id = ?
                   AND s.tenant_id = ?
                   AND s.user_id = ?
                   AND s.deleted = FALSE
                   AND m.role = 'ASSISTANT'
                """, rating, rating, messageId, tenant, userId) > 0;
    }
}
