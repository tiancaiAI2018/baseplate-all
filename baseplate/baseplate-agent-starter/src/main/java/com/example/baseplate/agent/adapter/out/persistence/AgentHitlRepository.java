package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

/**
 * HITL 审批持久化。
 *
 * <p>审批单必须落库：用户可能刷新页面、切标签页，模型上下文已经带着「待确认工具调用」
 * 存在 PostgreSQL 里，审批状态不能只放在内存或前端。</p>
 */
public final class AgentHitlRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentHitlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 待确认的工具调用快照，恢复时重建 ToolUseBlock 用。 */
    public record PendingCall(String callId, String tool, Map<String, Object> args) {
    }

    public record HitlRow(long id, String tenantId, long sessionId, String userId, long agentId,
                          String replyId, List<PendingCall> toolCalls, String status,
                          OffsetDateTime expiresAt) {
    }

    private static final RowMapper<HitlRow> MAPPER = (rs, i) -> new HitlRow(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getLong("session_id"),
            rs.getString("user_id"),
            rs.getLong("agent_id"),
            rs.getString("reply_id"),
            List.of(), // tool_calls 在 mapRow 里单独解析，见 query()
            rs.getString("status"),
            rs.getObject("expires_at", OffsetDateTime.class));

    public long create(String tenant, long sessionId, String userId, long agentId,
                       String replyId, List<PendingCall> calls, OffsetDateTime expiresAt) {
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_hitl_request
                    (tenant_id, session_id, user_id, agent_id, reply_id, tool_calls, expires_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id
                """, Long.class, tenant, sessionId, userId, agentId, replyId,
                mapper.writeValueAsString(calls), expiresAt);
        return id == null ? 0L : id;
    }

    /** 只查本人、未过期、仍待确认的审批单，避免拿别人的 id 越权。 */
    public Optional<HitlRow> findPending(String tenant, String userId, long id) {
        List<HitlRow> rows = jdbc.query("""
                SELECT * FROM agent_hitl_request
                 WHERE id = ? AND tenant_id = ? AND user_id = ?
                   AND status = 'PENDING' AND expires_at > now()
                """, (rs, i) -> new HitlRow(
                rs.getLong("id"), rs.getString("tenant_id"), rs.getLong("session_id"),
                rs.getString("user_id"), rs.getLong("agent_id"), rs.getString("reply_id"),
                parseCalls(rs.getString("tool_calls")), rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class)), id, tenant, userId);
        return rows.stream().findFirst();
    }

    /** 原子更新：只有仍为 PENDING 才能落决策，防止重复点击产生两条恢复流。 */
    public boolean markDecided(long id, String status, String comment, String decidedBy) {
        return jdbc.update("""
                UPDATE agent_hitl_request
                   SET status = ?, comment = ?, decided_by = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, status, comment, decidedBy, id) > 0;
    }

    /** 刷新页面后重新找回待审批项，避免审批卡消失。 */
    public List<HitlRow> listPending(String tenant, String userId, long sessionId) {
        return jdbc.query("""
                SELECT * FROM agent_hitl_request
                 WHERE tenant_id = ? AND user_id = ? AND session_id = ?
                   AND status = 'PENDING' AND expires_at > now()
                 ORDER BY id DESC
                """, (rs, i) -> new HitlRow(
                rs.getLong("id"), rs.getString("tenant_id"), rs.getLong("session_id"),
                rs.getString("user_id"), rs.getLong("agent_id"), rs.getString("reply_id"),
                parseCalls(rs.getString("tool_calls")), rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class)), tenant, userId, sessionId);
    }

    private List<PendingCall> parseCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> raw = mapper.readValue(json, List.class);
        return raw.stream().map(item -> new PendingCall(
                String.valueOf(item.get("callId")),
                String.valueOf(item.get("tool")),
                item.get("args") instanceof Map<?, ?> args ? castMap(args) : Map.of())).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
