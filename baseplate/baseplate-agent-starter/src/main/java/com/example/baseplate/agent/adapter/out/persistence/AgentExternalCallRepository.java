package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 外部工具调用：服务端只记状态与参数，结果由前端执行后回传。 */
public final class AgentExternalCallRepository {

    private final JdbcTemplate jdbc;

    public AgentExternalCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ExternalCallRow(long id, long sessionId, String replyId, String callId,
                                  String toolCode, String argsJson, String status,
                                  String resultJson, String errorMessage, OffsetDateTime expiresAt) {
    }

    private static final RowMapper<ExternalCallRow> MAPPER = (rs, i) -> new ExternalCallRow(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("reply_id"),
            rs.getString("call_id"),
            rs.getString("tool_code"),
            rs.getString("args"),
            rs.getString("status"),
            rs.getString("result"),
            rs.getString("error_message"),
            rs.getObject("expires_at", OffsetDateTime.class));

    public void create(String tenant, long sessionId, String userId, long agentId,
                       String replyId, String callId, String toolCode, String argsJson,
                       OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO agent_external_call
                    (tenant_id, session_id, user_id, agent_id, reply_id, call_id, tool_code,
                     args, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (session_id, call_id) DO NOTHING
                """, tenant, sessionId, userId, agentId, replyId, callId, toolCode,
                argsJson, expiresAt);
    }

    public Optional<ExternalCallRow> findPending(String tenant, String userId, String callId) {
        return jdbc.query("""
                SELECT * FROM agent_external_call
                 WHERE tenant_id = ? AND user_id = ? AND call_id = ?
                   AND status = 'PENDING' AND expires_at > now()
                """, MAPPER, tenant, userId, callId).stream().findFirst();
    }

    public boolean complete(String tenant, String userId, String callId,
                            boolean ok, String resultJson, String errorMessage) {
        return jdbc.update("""
                UPDATE agent_external_call
                   SET status = ?, result = ?::jsonb, error_message = ?, completed_at = now()
                 WHERE tenant_id = ? AND user_id = ? AND call_id = ? AND status = 'PENDING'
                """, ok ? "DONE" : "FAILED", resultJson, errorMessage,
                tenant, userId, callId) > 0;
    }

    public List<ExternalCallRow> listByReply(long sessionId, String replyId) {
        return jdbc.query("""
                SELECT * FROM agent_external_call
                 WHERE session_id = ? AND reply_id = ?
                 ORDER BY id
                """, MAPPER, sessionId, replyId);
    }
}
