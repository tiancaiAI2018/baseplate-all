package com.example.baseplate.agent.adapter.out.persistence;

import com.example.baseplate.agent.domain.AgentSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 会话持久化：所有查询都带 tenant + user，避免越权读到别人的对话。 */
public final class AgentSessionRepository {

    private final JdbcTemplate jdbc;

    public AgentSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AgentSession> MAPPER = (rs, i) -> new AgentSession(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getLong("agent_id"),
            rs.getString("user_id"),
            rs.getString("page_key"),
            rs.getString("title"),
            rs.getInt("message_count"),
            rs.getObject("last_active_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    public long create(String tenant, long agentId, String userId, String pageKey) {
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_session (tenant_id, agent_id, user_id, page_key)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, tenant, agentId, userId, pageKey);
        return id == null ? 0L : id;
    }

    public Optional<AgentSession> findOwned(String tenant, String userId, long id) {
        return jdbc.query("""
                SELECT * FROM agent_session
                 WHERE tenant_id = ? AND user_id = ? AND id = ? AND deleted = FALSE
                """, MAPPER, tenant, userId, id).stream().findFirst();
    }

    public Optional<AgentSession> find(String tenant, long id) {
        return jdbc.query("""
                SELECT * FROM agent_session
                 WHERE tenant_id = ? AND id = ? AND deleted = FALSE
                """, MAPPER, tenant, id).stream().findFirst();
    }

    public List<AgentSession> listOwned(String tenant, String userId, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_session
                 WHERE tenant_id = ? AND user_id = ? AND deleted = FALSE
                 ORDER BY last_active_at DESC
                 LIMIT ?
                """, MAPPER, tenant, userId, limit);
    }

    public List<AgentSession> listAll(String tenant, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_session
                 WHERE tenant_id = ? AND deleted = FALSE
                 ORDER BY last_active_at DESC
                 LIMIT ?
                """, MAPPER, tenant, limit);
    }

    /** 每轮结束后更新标题、消息数与活跃时间；标题只写一次（首条用户消息）。 */
    public void touch(long sessionId, int addedMessages, String titleIfBlank) {
        jdbc.update("""
                UPDATE agent_session
                   SET message_count = message_count + ?,
                       title = CASE WHEN title IS NULL OR title = '' THEN ? ELSE title END,
                       last_active_at = now()
                 WHERE id = ?
                """, addedMessages, titleIfBlank, sessionId);
    }

    /** 继续旧会话时用户可能已经换了页面；同步 pageKey，恢复流才会按当前页面注册工具。 */
    public void updatePageKey(String tenant, long sessionId, String pageKey) {
        jdbc.update("""
                UPDATE agent_session SET page_key = ?, last_active_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, pageKey, tenant, sessionId);
    }

    public void softDelete(String tenant, String userId, long id) {
        jdbc.update("""
                UPDATE agent_session SET deleted = TRUE
                 WHERE tenant_id = ? AND user_id = ? AND id = ?
                """, tenant, userId, id);
    }
}
