package com.example.baseplate.workflow.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 签到、坐席状态、拒绝事实、惩罚。和 DispatchRepository 分开，
 * 是因为这几张表的读写频率和事务边界不同：签到是高频小事务，分单日志是追加写。
 */
public final class AttendanceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 签到 ----------------

    public Optional<Map<String, Object>> checkinOf(String tenant, String userId, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id AS \"userId\","
                + " biz_date AS \"bizDate\", checkin_at AS \"checkinAt\", checkout_at AS \"checkoutAt\","
                + " source FROM wf_checkin WHERE tenant_id = ? AND user_id = ? AND biz_date = ?",
                tenant, userId, date);
        return rows.stream().findFirst();
    }

    public void checkin(String tenant, String userId, LocalDate date, String source) {
        // 同日签退后再次签到要清空 checkout_at，否则 day 判断 checkoutAt == null 会一直显示未签到。
        jdbc.update("INSERT INTO wf_checkin(tenant_id, user_id, biz_date, source)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, user_id, biz_date) DO UPDATE SET checkout_at = NULL,"
                + " source = EXCLUDED.source",
                tenant, userId, date, source);
        ensureAgentState(tenant, userId);
        jdbc.update("UPDATE wf_agent_state SET last_heartbeat = now(), online_status = 'ONLINE',"
                + " updated_at = now() WHERE tenant_id = ? AND user_id = ?", tenant, userId);
    }

    public void checkout(String tenant, String userId, LocalDate date) {
        jdbc.update("UPDATE wf_checkin SET checkout_at = now()"
                + " WHERE tenant_id = ? AND user_id = ? AND biz_date = ? AND checkout_at IS NULL",
                tenant, userId, date);
        jdbc.update("UPDATE wf_agent_state SET online_status = 'OFFLINE', last_heartbeat = now(),"
                + " updated_at = now() WHERE tenant_id = ? AND user_id = ?", tenant, userId);
    }

    public List<Map<String, Object>> checkinHistory(String tenant, String userId, int days) {
        return jdbc.queryForList("SELECT biz_date AS \"bizDate\", checkin_at AS \"checkinAt\","
                + " checkout_at AS \"checkoutAt\", source FROM wf_checkin"
                + " WHERE tenant_id = ? AND user_id = ? AND biz_date >= CURRENT_DATE - ?"
                + " ORDER BY biz_date DESC", tenant, userId, Math.max(1, days));
    }

    public int checkedInCount(String tenant, LocalDate date) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wf_checkin"
                + " WHERE tenant_id = ? AND biz_date = ? AND checkout_at IS NULL", Integer.class, tenant, date);
        return count == null ? 0 : count;
    }

    // ---------------- 坐席状态 ----------------

    public void ensureAgentState(String tenant, String userId) {
        jdbc.update("INSERT INTO wf_agent_state(tenant_id, user_id, online_status)"
                + " VALUES (?, ?, 'OFFLINE') ON CONFLICT (tenant_id, user_id) DO NOTHING", tenant, userId);
    }

    public Optional<Map<String, Object>> agentState(String tenant, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id AS \"userId\","
                + " online_status AS \"onlineStatus\", last_heartbeat AS \"lastHeartbeat\","
                + " skills::text AS \"skills\", capacity, weight, rating, rating_count AS \"ratingCount\","
                + " consecutive_refusals AS \"consecutiveRefusals\", reject_count AS \"rejectCount\","
                + " cooldown_until AS \"cooldownUntil\" FROM wf_agent_state"
                + " WHERE tenant_id = ? AND user_id = ?", tenant, userId);
        return rows.stream().findFirst();
    }

    public void heartbeat(String tenant, String userId, String status) {
        ensureAgentState(tenant, userId);
        jdbc.update("UPDATE wf_agent_state SET online_status = ?, last_heartbeat = now(),"
                + " updated_at = now() WHERE tenant_id = ? AND user_id = ?", status, tenant, userId);
    }

    public void clearConsecutiveRefusals(String tenant, String userId) {
        jdbc.update("UPDATE wf_agent_state SET consecutive_refusals = 0, updated_at = now()"
                + " WHERE tenant_id = ? AND user_id = ?", tenant, userId);
    }

    public void markRefusal(String tenant, String userId) {
        ensureAgentState(tenant, userId);
        jdbc.update("UPDATE wf_agent_state SET consecutive_refusals = consecutive_refusals + 1,"
                + " reject_count = reject_count + 1, last_refusal_at = now(), updated_at = now()"
                + " WHERE tenant_id = ? AND user_id = ?", tenant, userId);
    }

    public void updateRating(String tenant, String userId, int score) {
        ensureAgentState(tenant, userId);
        jdbc.update("UPDATE wf_agent_state SET rating = ROUND((rating * rating_count + ?)"
                + " / (rating_count + 1), 2), rating_count = rating_count + 1, updated_at = now()"
                + " WHERE tenant_id = ? AND user_id = ?", score, tenant, userId);
    }

    // ---------------- 惩罚 ----------------

    public long addPenalty(String tenant, String userId, Long poolId, String type, Double value,
                           String reason, Long sourceRefusalId, Instant expiresAt, String operator) {
        Long id = jdbc.queryForObject("INSERT INTO wf_agent_penalty(tenant_id, user_id, pool_id,"
                        + " penalty_type, value, reason, source_refusal_id, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, tenant, userId, poolId, type, value, reason, sourceRefusalId,
                expiresAt == null ? null : Timestamp.from(expiresAt));
        return id == null ? 0L : id;
    }

    public List<Map<String, Object>> activePenalties(String tenant, String userId) {
        return jdbc.queryForList("SELECT id, user_id AS \"userId\", pool_id AS \"poolId\","
                + " penalty_type AS \"penaltyType\", value, reason, started_at AS \"startedAt\","
                + " expires_at AS \"expiresAt\" FROM wf_agent_penalty"
                + " WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL"
                + " AND (expires_at IS NULL OR expires_at > now()) ORDER BY id DESC", tenant, userId);
    }

    public List<Map<String, Object>> penalties(String tenant, String userId, int limit) {
        String filter = userId == null ? "" : userId;
        return jdbc.queryForList("SELECT id, user_id AS \"userId\", pool_id AS \"poolId\","
                + " penalty_type AS \"penaltyType\", value, reason, started_at AS \"startedAt\","
                + " expires_at AS \"expiresAt\", revoked_at AS \"revokedAt\","
                + " revoked_by AS \"revokedBy\" FROM wf_agent_penalty WHERE tenant_id = ?"
                + " AND (? = '' OR user_id = ?) ORDER BY id DESC LIMIT ?",
                tenant, filter, filter, Math.min(Math.max(limit, 1), 500));
    }

    public boolean revokePenalty(String tenant, long id, String operator) {
        return jdbc.update("UPDATE wf_agent_penalty SET revoked_at = now(), revoked_by = ?"
                + " WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL", operator, tenant, id) > 0;
    }

    public Optional<Map<String, Object>> penalty(String tenant, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, user_id AS \"userId\","
                + " pool_id AS \"poolId\", penalty_type AS \"penaltyType\", value"
                + " FROM wf_agent_penalty WHERE tenant_id = ? AND id = ?", tenant, id);
        return rows.stream().findFirst();
    }

    public void resetWeight(String tenant, String userId, double weight) {
        jdbc.update("UPDATE wf_agent_state SET weight = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND user_id = ?", weight, tenant, userId);
    }

    /** 生效中的权重系数：WEIGHT 类惩罚连乘，最低 0.1，避免被扣到永无出头之日。 */
    public double effectiveWeight(String tenant, String userId, Long poolId) {
        List<Double> values = jdbc.queryForList("SELECT value FROM wf_agent_penalty"
                + " WHERE tenant_id = ? AND user_id = ? AND penalty_type = 'WEIGHT'"
                + " AND (pool_id IS NULL OR pool_id = ?) AND revoked_at IS NULL"
                + " AND (expires_at IS NULL OR expires_at > now())", Double.class, tenant, userId, poolId);
        double weight = 1.0;
        for (Double value : values) {
            if (value != null) {
                weight *= value;
            }
        }
        return Math.max(0.1, Math.min(1.0, weight));
    }

    public void applyCooldown(String tenant, String userId, Long poolId, Instant until, String reason,
                              Long refusalId) {
        addPenalty(tenant, userId, poolId, "COOLDOWN", null, reason, refusalId, until, null);
        ensureAgentState(tenant, userId);
        jdbc.update("UPDATE wf_agent_state SET cooldown_until = GREATEST("
                + "COALESCE(cooldown_until, now()), ?), updated_at = now()"
                + " WHERE tenant_id = ? AND user_id = ?", Timestamp.from(until), tenant, userId);
    }

    public void applyWeight(String tenant, String userId, Long poolId, double multiplier,
                            Instant expiresAt, String reason, Long refusalId) {
        addPenalty(tenant, userId, poolId, "WEIGHT", multiplier, reason, refusalId, expiresAt, null);
        double weight = effectiveWeight(tenant, userId, poolId);
        jdbc.update("UPDATE wf_agent_state SET weight = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND user_id = ?", weight, tenant, userId);
    }

    public void applyBlock(String tenant, String userId, Long poolId, Instant expiresAt,
                           String reason, Long refusalId) {
        addPenalty(tenant, userId, poolId, "BLOCK", null, reason, refusalId, expiresAt, null);
        ensureAgentState(tenant, userId);
        jdbc.update("UPDATE wf_agent_state SET cooldown_until = GREATEST("
                        + "COALESCE(cooldown_until, now()), ?), updated_at = now()"
                        + " WHERE tenant_id = ? AND user_id = ?",
                Timestamp.from(expiresAt == null ? Instant.now().plusSeconds(86400) : expiresAt),
                tenant, userId);
    }

    // ---------------- 拒绝事实 ----------------

    public long addRefusal(String tenant, long poolId, String taskId, String instanceId, String userId,
                           String refusalType, String reasonCode, String reasonText,
                           Object policySnapshot, Object effect) {
        Long id = jdbc.queryForObject("INSERT INTO wf_dispatch_refusal(tenant_id, pool_id, task_id,"
                        + " instance_id, user_id, refusal_type, reason_code, reason_text,"
                        + " policy_snapshot, effect_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)"
                        + " RETURNING id",
                Long.class, tenant, poolId, taskId, instanceId, userId, refusalType,
                reasonCode, reasonText, write(policySnapshot), write(effect));
        return id == null ? 0L : id;
    }

    public boolean hasRefused(String tenant, String taskId, String userId) {
        return hasAnyRefusal(tenant, taskId, userId);
    }

    public boolean hasAnyRefusal(String tenant, String taskId, String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wf_dispatch_refusal"
                + " WHERE tenant_id = ? AND task_id = ? AND user_id = ?", Integer.class, tenant, taskId, userId);
        return count != null && count > 0;
    }

    public void updateRefusalEffect(String tenant, long id, Object effect) {
        jdbc.update("UPDATE wf_dispatch_refusal SET effect_json = ?::jsonb"
                + " WHERE tenant_id = ? AND id = ?", write(effect), tenant, id);
    }

    public int refusalCount(String tenant, String userId, int days) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wf_dispatch_refusal"
                        + " WHERE tenant_id = ? AND user_id = ? AND created_at > now() - (? * interval '1 day')",
                Integer.class, tenant, userId, Math.max(1, days));
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> refusals(String tenant, String userId, Long poolId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, pool_id AS \"poolId\","
                + " task_id AS \"taskId\", instance_id AS \"instanceId\", user_id AS \"userId\","
                + " refusal_type AS \"refusalType\", reason_code AS \"reasonCode\","
                + " reason_text AS \"reasonText\", effect_json::text AS \"effect\","
                + " created_at AS \"createdAt\" FROM wf_dispatch_refusal WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        if (poolId != null) {
            sql.append(" AND pool_id = ?");
            args.add(poolId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> refusalStats(String tenant, int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count("SELECT COUNT(*) FROM wf_dispatch_refusal WHERE tenant_id = ?"
                + " AND created_at > now() - (? * interval '1 day')", tenant, days));
        result.put("users", jdbc.queryForList("SELECT user_id AS \"userId\", COUNT(*) AS \"count\""
                + " FROM wf_dispatch_refusal WHERE tenant_id = ?"
                + " AND created_at > now() - (? * interval '1 day')"
                + " GROUP BY user_id ORDER BY COUNT(*) DESC LIMIT 20", tenant, days));
        result.put("reasons", jdbc.queryForList("SELECT reason_code AS \"reasonCode\", COUNT(*) AS \"count\""
                + " FROM wf_dispatch_refusal WHERE tenant_id = ?"
                + " AND created_at > now() - (? * interval '1 day')"
                + " GROUP BY reason_code ORDER BY COUNT(*) DESC", tenant, days));
        return result;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException ex) {
            return "{}";
        }
    }
}
