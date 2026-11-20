package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 智能体观测查询：会话 + 运行指标 + 评分的聚合视图。
 *
 * <p>聚合结果按会话分组：agent_run 汇总 token / 耗时 / 错误，
 * agent_message 汇总「有用 / 没用」评分。数据量上来后可加
 * created_at 范围条件或物化，这里先保持单表聚合的简单实现。</p>
 */
public final class AgentObserveRepository {

    private final JdbcTemplate jdbc;

    public AgentObserveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 会话观测行：一行 = 一个会话 + 它的运行与评分汇总。 */
    public record ObserveSessionRow(
            long id,
            String agentCode,
            String agentName,
            String userId,
            String userName,
            String pageKey,
            String title,
            int messageCount,
            OffsetDateTime lastActiveAt,
            OffsetDateTime createdAt,
            long runCount,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long errorCount,
            Long avgLatencyMs,
            long usefulCount,
            long notUsefulCount) {
    }

    private static final RowMapper<ObserveSessionRow> ROW_MAPPER = (rs, i) -> new ObserveSessionRow(
            rs.getLong("id"),
            rs.getString("agent_code"),
            rs.getString("agent_name"),
            rs.getString("user_id"),
            rs.getString("user_name"),
            rs.getString("page_key"),
            rs.getString("title"),
            rs.getInt("message_count"),
            rs.getObject("last_active_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getLong("run_count"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cached_tokens"),
            rs.getLong("error_count"),
            rs.getObject("avg_latency_ms") == null ? null : rs.getLong("avg_latency_ms"),
            rs.getLong("useful_count"),
            rs.getLong("not_useful_count"));

    /**
     * 会话列表（含聚合），按页返回。过滤条件都可选：agent 精确、用户精确、标题模糊。
     * offset 由调用方按 (page-1)*limit 计算。
     */
    public PagedSessions listSessions(String tenant, Long agentId, String userId,
                                      String keyword, int limit, long offset) {
        StringBuilder where = new StringBuilder(" WHERE s.tenant_id = ? AND s.deleted = FALSE");
        List<Object> params = new ArrayList<>();
        params.add(tenant);
        if (agentId != null) {
            where.append(" AND s.agent_id = ?");
            params.add(agentId);
        }
        if (userId != null && !userId.isBlank()) {
            where.append(" AND s.user_id = ?");
            params.add(userId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND s.title ILIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_session s" + where, Long.class, params.toArray());
        List<ObserveSessionRow> items = jdbc.query(
                BASE_QUERY + where + " ORDER BY s.last_active_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, appendParams(params, limit, offset).toArray());
        return new PagedSessions(total == null ? 0 : total, items);
    }

    public Optional<ObserveSessionRow> findSession(String tenant, long sessionId) {
        return jdbc.query(BASE_QUERY + " WHERE s.tenant_id = ? AND s.deleted = FALSE AND s.id = ?",
                ROW_MAPPER, tenant, sessionId).stream().findFirst();
    }

    /** 分页结果：总数 + 当前页数据。 */
    public record PagedSessions(long total, List<ObserveSessionRow> items) {
    }

    private static List<Object> appendParams(List<Object> params, int limit, long offset) {
        List<Object> copy = new ArrayList<>(params);
        copy.add(limit);
        copy.add(offset);
        return copy;
    }

    /**
     * 聚合口径统一放在一个模板里：listSessions 与 findSession 拼接各自的
     * WHERE 条件，保证两处数字永远一致。
     * user_name：user_id 存的是 sys_user.id 的字符串形式，join 显示名优先。
     */
    private static final String BASE_QUERY = """
            SELECT s.id, d.agent_code, d.name AS agent_name, s.user_id,
                   COALESCE(NULLIF(u.display_name, ''), u.username, s.user_id) AS user_name,
                   s.page_key,
                   s.title, s.message_count, s.last_active_at, s.created_at,
                   COALESCE(r.run_count, 0)       AS run_count,
                   COALESCE(r.input_tokens, 0)    AS input_tokens,
                   COALESCE(r.output_tokens, 0)   AS output_tokens,
                   COALESCE(r.cached_tokens, 0)   AS cached_tokens,
                   COALESCE(r.error_count, 0)     AS error_count,
                   r.avg_latency_ms,
                   COALESCE(f.useful_count, 0)     AS useful_count,
                   COALESCE(f.not_useful_count, 0) AS not_useful_count
            FROM agent_session s
            JOIN agent_definition d ON d.id = s.agent_id
            LEFT JOIN sys_user u ON u.tenant_id = s.tenant_id AND CAST(u.id AS VARCHAR) = s.user_id
            LEFT JOIN (
                SELECT session_id,
                       COUNT(*) AS run_count,
                       SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens,
                       SUM(cached_tokens) AS cached_tokens,
                       COUNT(*) FILTER (WHERE status <> 'OK') AS error_count,
                       AVG(latency_ms)::bigint AS avg_latency_ms
                FROM agent_run GROUP BY session_id
            ) r ON r.session_id = s.id
            LEFT JOIN (
                SELECT session_id,
                       COUNT(*) FILTER (WHERE feedback = 'USEFUL') AS useful_count,
                       COUNT(*) FILTER (WHERE feedback = 'NOT_USEFUL') AS not_useful_count
                FROM agent_message WHERE role = 'ASSISTANT' GROUP BY session_id
            ) f ON f.session_id = s.id
            """;
}
