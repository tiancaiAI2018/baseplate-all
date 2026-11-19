package com.example.baseplate.agent.adapter.out.persistence;

import com.example.baseplate.agent.domain.AgentMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

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
            rs.getObject("created_at", OffsetDateTime.class));

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
}
