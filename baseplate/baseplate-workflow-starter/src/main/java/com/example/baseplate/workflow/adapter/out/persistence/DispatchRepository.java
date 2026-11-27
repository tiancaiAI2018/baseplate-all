package com.example.baseplate.workflow.adapter.out.persistence;

import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.workflow.application.dispatch.DispatchPolicy;
import com.example.baseplate.workflow.application.dispatch.WorkPool;
import com.example.baseplate.workflow.application.dispatch.WorkPoolOrg;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分单池、策略、池任务、决策日志、评价的持久化。
 * 签到/坐席状态/拒绝/惩罚在 AttendanceRepository，避免单类过长。
 *
 * 组织边界：所有候选查询都必须带 org_id IN (...)，由 WorkPoolResolver 展开，
 * 任何「查全量用户再过滤」的写法都视为越权。
 */
public final class DispatchRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 池 ----------------

    public List<WorkPool> pools(String tenant) {
        Map<Long, List<WorkPoolOrg>> orgs = new LinkedHashMap<>();
        jdbc.query("SELECT pool_id, org_id, include_children FROM wf_work_pool_org"
                        + " WHERE tenant_id = ? ORDER BY pool_id, org_id",
                rs -> {
                    orgs.computeIfAbsent(rs.getLong("pool_id"), key -> new ArrayList<>())
                            .add(new WorkPoolOrg(rs.getLong("org_id"), rs.getBoolean("include_children")));
                }, tenant);
        return jdbc.query("SELECT id, code, name, description, status, claim_enabled"
                        + " FROM wf_work_pool WHERE tenant_id = ? ORDER BY id",
                (rs, i) -> new WorkPool(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("status"), rs.getBoolean("claim_enabled"),
                        orgs.getOrDefault(rs.getLong("id"), List.of())), tenant);
    }

    public Optional<WorkPool> poolByCode(String tenant, String code) {
        return pools(tenant).stream().filter(pool -> pool.code().equals(code)).findFirst();
    }

    public Optional<WorkPool> pool(String tenant, long id) {
        return pools(tenant).stream().filter(pool -> pool.id() == id).findFirst();
    }

    public long savePool(String tenant, String code, String name, String description,
                         String status, boolean claimEnabled, String operator) {
        Long id = jdbc.queryForObject("INSERT INTO wf_work_pool(tenant_id, code, name, description,"
                        + " status, claim_enabled, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name,"
                        + " description = EXCLUDED.description, status = EXCLUDED.status,"
                        + " claim_enabled = EXCLUDED.claim_enabled, updated_at = now() RETURNING id",
                Long.class, tenant, code, name, description, status, claimEnabled, operator);
        return id == null ? 0L : id;
    }

    public void savePoolOrgs(String tenant, long poolId, List<WorkPoolOrg> orgs) {
        jdbc.update("DELETE FROM wf_work_pool_org WHERE tenant_id = ? AND pool_id = ?", tenant, poolId);
        if (orgs == null || orgs.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (WorkPoolOrg org : orgs) {
            if (org.orgId() != null) {
                batch.add(new Object[]{tenant, poolId, org.orgId(), org.includeChildren()});
            }
        }
        jdbc.batchUpdate("INSERT INTO wf_work_pool_org(tenant_id, pool_id, org_id, include_children)"
                + " VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, pool_id, org_id) DO UPDATE"
                + " SET include_children = EXCLUDED.include_children", batch);
    }

    // ---------------- 策略 ----------------

    /** 精确版本优先，找不到退回草稿（version=0），和节点配置同一套规则。 */
    public Optional<DispatchPolicy> policy(String tenant, String processKey, int version, String nodeId) {
        Optional<DispatchPolicy> exact = policyOf(tenant, processKey, version, nodeId);
        if (exact.isEmpty() && version > 0) {
            return policyOf(tenant, processKey, 0, nodeId);
        }
        return exact;
    }

    private Optional<DispatchPolicy> policyOf(String tenant, String processKey, int version, String nodeId) {
        List<DispatchPolicy> rows = jdbc.query("SELECT id, pool_id, mode, algorithm, policy_json::text"
                        + " FROM wf_dispatch_policy WHERE tenant_id = ? AND process_key = ?"
                        + " AND definition_version = ? AND node_id = ? AND enabled = TRUE",
                (rs, i) -> new DispatchPolicy(rs.getLong("id"), rs.getLong("pool_id"),
                        rs.getString("mode"), rs.getString("algorithm"),
                        json(rs.getString("policy_json"))),
                tenant, processKey, version, nodeId);
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> policies(String tenant, String processKey) {
        return jdbc.query("SELECT id, definition_version, node_id, pool_id, mode, algorithm,"
                        + " policy_json::text AS policy_json, enabled, updated_by, updated_at"
                        + " FROM wf_dispatch_policy WHERE tenant_id = ? AND process_key = ?"
                        + " ORDER BY definition_version, node_id",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("processKey", processKey);
                    row.put("definitionVersion", rs.getInt("definition_version"));
                    row.put("nodeId", rs.getString("node_id"));
                    row.put("poolId", rs.getLong("pool_id"));
                    row.put("mode", rs.getString("mode"));
                    row.put("algorithm", rs.getString("algorithm"));
                    row.put("config", json(rs.getString("policy_json")));
                    row.put("enabled", rs.getBoolean("enabled"));
                    row.put("updatedBy", rs.getString("updated_by"));
                    row.put("updatedAt", rs.getTimestamp("updated_at"));
                    return row;
                }, tenant, processKey);
    }

    public void savePolicy(String tenant, String processKey, int version, String nodeId,
                           long poolId, String mode, String algorithm,
                           Map<String, Object> config, String operator) {
        jdbc.update("INSERT INTO wf_dispatch_policy(tenant_id, process_key, definition_version,"
                        + " node_id, pool_id, mode, algorithm, policy_json, updated_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)"
                        + " ON CONFLICT (tenant_id, process_key, definition_version, node_id) DO UPDATE SET"
                        + " pool_id = EXCLUDED.pool_id, mode = EXCLUDED.mode, algorithm = EXCLUDED.algorithm,"
                        + " policy_json = EXCLUDED.policy_json, updated_by = EXCLUDED.updated_by,"
                        + " updated_at = now()",
                tenant, processKey, version, nodeId, poolId, mode.toUpperCase(),
                algorithm.toUpperCase(), write(config), operator);
    }

    // ---------------- 候选 ----------------

    public List<DispatchCandidate> candidates(String tenant, Collection<Long> orgIds, LocalDate today) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(orgIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(today);
        args.add(tenant);
        args.addAll(orgIds);
        return jdbc.query("SELECT CAST(u.id AS VARCHAR) AS user_id,"
                        + " COALESCE(NULLIF(u.display_name, ''), u.username) AS display_name, u.org_id,"
                        + " EXISTS (SELECT 1 FROM wf_checkin c WHERE c.tenant_id = u.tenant_id"
                        + "   AND c.user_id = CAST(u.id AS VARCHAR) AND c.biz_date = ?"
                        + "   AND c.checkout_at IS NULL) AS checked_in,"
                        + " COALESCE(s.online_status, 'OFFLINE') AS online_status, s.last_heartbeat,"
                        + " (SELECT COUNT(*) FROM act_ru_task t WHERE t.tenant_id_ = u.tenant_id"
                        + "   AND t.assignee_ = CAST(u.id AS VARCHAR) AND t.suspension_state_ = 1)"
                        + "   AS active_count,"
                        + " COALESCE(s.capacity, 10) AS capacity, COALESCE(s.weight, 1) AS weight,"
                        + " COALESCE(s.rating, 5) AS rating, COALESCE(s.rating_count, 0) AS rating_count,"
                        + " COALESCE(s.consecutive_refusals, 0) AS consecutive_refusals,"
                        + " s.cooldown_until, COALESCE(s.skills, '[]'::jsonb)::text AS skills,"
                        + " (SELECT COUNT(*) FROM wf_dispatch_log l WHERE l.tenant_id = u.tenant_id"
                        + "   AND l.chosen_user_id = CAST(u.id AS VARCHAR)"
                        + "   AND l.created_at > now() - interval '7 days') AS recent_assigned"
                        + " FROM sys_user u LEFT JOIN wf_agent_state s"
                        + "   ON s.tenant_id = u.tenant_id AND s.user_id = CAST(u.id AS VARCHAR)"
                        + " WHERE u.tenant_id = ? AND u.status = 'ACTIVE'"
                        + "   AND u.org_id IN (" + placeholders + ")"
                        + " ORDER BY u.id",
                (rs, i) -> new DispatchCandidate(rs.getString("user_id"), rs.getString("display_name"),
                        (Long) rs.getObject("org_id", Long.class), rs.getBoolean("checked_in"),
                        rs.getString("online_status"), instant(rs.getTimestamp("last_heartbeat")),
                        rs.getInt("active_count"), rs.getInt("capacity"), decimal(rs.getBigDecimal("weight")),
                        decimal(rs.getBigDecimal("rating")), rs.getInt("rating_count"),
                        rs.getInt("consecutive_refusals"), instant(rs.getTimestamp("cooldown_until")),
                        stringList(rs.getString("skills")), rs.getInt("recent_assigned")),
                args.toArray());
    }

    public int activeCount(String tenant, String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_task"
                + " WHERE tenant_id_ = ? AND assignee_ = ? AND suspension_state_ = 1",
                Integer.class, tenant, userId);
        return count == null ? 0 : count;
    }

    /** 池内组织（含子组织展开后的叶子集合），供看板计算「可派人数」。 */
    public List<Long> resolveOrgIds(String tenant, List<WorkPoolOrg> orgs) {
        if (orgs == null || orgs.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (WorkPoolOrg org : orgs) {
            if (org.orgId() == null) {
                continue;
            }
            result.add(org.orgId());
            if (org.includeChildren()) {
                List<String> paths = jdbc.queryForList("SELECT path FROM sys_org"
                        + " WHERE tenant_id = ? AND id = ?", String.class, tenant, org.orgId());
                if (!paths.isEmpty() && paths.get(0) != null) {
                    String path = paths.get(0);
                    result.addAll(jdbc.queryForList("SELECT id FROM sys_org WHERE tenant_id = ?"
                                    + " AND (path = ? OR path LIKE ?) AND id <> ?",
                            Long.class, tenant, path, path + "/%", org.orgId()));
                }
            }
        }
        return result.stream().distinct().toList();
    }

    // ---------------- 池任务 ----------------

    public void addPoolTask(String tenant, long poolId, String taskId, String instanceId,
                            String nodeId, String requesterId, int priority,
                            String status, String claimedBy) {
        jdbc.update("INSERT INTO wf_pool_task(tenant_id, pool_id, task_id, instance_id, node_id,"
                        + " requester_id, priority, status, claimed_by, claimed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'CLAIMED' THEN now() END)"
                        + " ON CONFLICT (tenant_id, task_id) DO UPDATE SET pool_id = EXCLUDED.pool_id,"
                        + " priority = EXCLUDED.priority, status = EXCLUDED.status,"
                        + " claimed_by = EXCLUDED.claimed_by, claimed_at = EXCLUDED.claimed_at,"
                        + " updated_at = now()",
                tenant, poolId, taskId, instanceId, nodeId, requesterId, priority,
                status, claimedBy, status);
    }

    public void reopenPoolTask(String tenant, String taskId) {
        jdbc.update("UPDATE wf_pool_task SET status = 'OPEN', claimed_by = NULL, claimed_at = NULL,"
                + " updated_at = now() WHERE tenant_id = ? AND task_id = ?", tenant, taskId);
    }

    public Optional<Map<String, Object>> poolTaskByTaskId(String tenant, String taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, pool_id AS \"poolId\","
                + " task_id AS \"taskId\", instance_id AS \"instanceId\", node_id AS \"nodeId\","
                + " requester_id AS \"requesterId\", priority, status, claimed_by AS \"claimedBy\","
                + " claimed_at AS \"claimedAt\" FROM wf_pool_task"
                + " WHERE tenant_id = ? AND task_id = ?", tenant, taskId);
        return rows.stream().findFirst();
    }

    public Long orgOfUser(String tenant, String userId) {
        List<Long> rows = jdbc.queryForList("SELECT org_id FROM sys_user"
                + " WHERE tenant_id = ? AND CAST(id AS VARCHAR) = ?", Long.class, tenant, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> orgs(String tenant) {
        return jdbc.queryForList("SELECT id, parent_id AS \"parentId\", name, path FROM sys_org"
                + " WHERE tenant_id = ? ORDER BY sort_no, id", tenant);
    }

    public List<Map<String, Object>> usersOfOrgIds(String tenant, Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(orgIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.addAll(orgIds);
        return jdbc.queryForList("SELECT CAST(id AS VARCHAR) AS \"userId\", username,"
                + " COALESCE(NULLIF(display_name, ''), username) AS \"displayName\","
                + " org_id AS \"orgId\" FROM sys_user WHERE tenant_id = ? AND status = 'ACTIVE'"
                + " AND org_id IN (" + placeholders + ") ORDER BY id", args.toArray());
    }

    public List<Map<String, Object>> poolTasks(String tenant, Collection<Long> poolIds, String status) {
        if (poolIds == null || poolIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(poolIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.addAll(poolIds);
        args.add(status);
        return jdbc.queryForList("SELECT pt.id, pt.task_id AS \"taskId\", pt.instance_id AS \"instanceId\","
                        + " pt.node_id AS \"nodeId\", pt.pool_id AS \"poolId\", pt.priority, pt.status,"
                        + " pt.claimed_by AS \"claimedBy\", pt.created_at AS \"createdAt\","
                        + " t.name_ AS \"taskName\", t.create_time_ AS \"taskCreatedAt\","
                        + " t.assignee_ AS \"assignee\""
                        + " FROM wf_pool_task pt LEFT JOIN act_ru_task t"
                        + "   ON t.id_ = pt.task_id AND t.tenant_id_ = pt.tenant_id"
                        + " WHERE pt.tenant_id = ? AND pt.pool_id IN (" + placeholders + ")"
                        + "   AND pt.status = ? ORDER BY pt.priority DESC, pt.created_at", args.toArray());
    }

    public Optional<Map<String, Object>> poolTask(String tenant, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, pool_id AS \"poolId\","
                + " task_id AS \"taskId\", instance_id AS \"instanceId\", node_id AS \"nodeId\","
                + " status, claimed_by AS \"claimedBy\" FROM wf_pool_task"
                + " WHERE tenant_id = ? AND id = ?", tenant, id);
        return rows.stream().findFirst();
    }

    /** 当前用户名下的分单任务（含办理入口与拒绝入口）。 */
    public List<Map<String, Object>> myTasks(String tenant, String userId) {
        return jdbc.queryForList("SELECT pt.id, pt.task_id AS \"taskId\", pt.instance_id AS \"instanceId\","
                + " pt.node_id AS \"nodeId\", pt.pool_id AS \"poolId\", pt.status, pt.priority,"
                + " pt.claimed_at AS \"claimedAt\", t.name_ AS \"taskName\","
                + " t.create_time_ AS \"taskCreatedAt\", t.assignee_ AS \"assignee\""
                + " FROM wf_pool_task pt JOIN act_ru_task t"
                + "   ON t.id_ = pt.task_id AND t.tenant_id_ = pt.tenant_id"
                + " WHERE pt.tenant_id = ? AND t.assignee_ = ? AND t.suspension_state_ = 1"
                + " ORDER BY pt.priority DESC, t.create_time_", tenant, userId);
    }

    public int claimPoolTask(String tenant, long id, String userId) {
        return jdbc.update("UPDATE wf_pool_task SET status = 'CLAIMED', claimed_by = ?, claimed_at = now()"
                + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'", userId, tenant, id);
    }

    public void closePoolTaskByTask(String tenant, String taskId, String status) {
        jdbc.update("UPDATE wf_pool_task SET status = ? WHERE tenant_id = ? AND task_id = ?"
                + " AND status IN ('OPEN', 'CLAIMED')", status, tenant, taskId);
    }

    public List<Map<String, Object>> expiredPoolTasks(int minutes) {
        return jdbc.queryForList("SELECT id, tenant_id AS \"tenantId\", pool_id AS \"poolId\","
                + " task_id AS \"taskId\", instance_id AS \"instanceId\", node_id AS \"nodeId\","
                + " status, claimed_by AS \"claimedBy\", created_at AS \"createdAt\""
                + " FROM wf_pool_task WHERE status = 'OPEN'"
                + " AND COALESCE(updated_at, created_at) < now() - (? * interval '1 minute')",
                Math.max(1, minutes));
    }

    /** 已指派但长时间未办理的任务，供超时回收使用。 */
    public List<Map<String, Object>> expiredClaimedTasks(int minutes) {
        return jdbc.queryForList("SELECT id, tenant_id AS \"tenantId\", pool_id AS \"poolId\","
                + " task_id AS \"taskId\", instance_id AS \"instanceId\", node_id AS \"nodeId\","
                + " status, claimed_by AS \"claimedBy\", claimed_at AS \"claimedAt\""
                + " FROM wf_pool_task WHERE status = 'CLAIMED'"
                + " AND claimed_at IS NOT NULL AND claimed_at < now() - (? * interval '1 minute')"
                + " ORDER BY claimed_at", Math.max(1, minutes));
    }

    /** 可评价任务：已办结、有办理人、当前用户还没评过。 */
    public List<Map<String, Object>> ratableTasks(String tenant, String raterId, int limit) {
        return jdbc.queryForList("SELECT pt.id, pt.task_id AS \"taskId\","
                + " pt.pool_id AS \"poolId\", pt.instance_id AS \"instanceId\","
                + " COALESCE(h.name_, pt.node_id) AS \"taskName\", h.assignee_ AS \"assignee\","
                + " h.end_time_ AS \"endTime\" FROM wf_pool_task pt"
                + " JOIN act_hi_taskinst h ON h.id_ = pt.task_id"
                + " WHERE pt.tenant_id = ? AND h.end_time_ IS NOT NULL"
                + " AND h.assignee_ IS NOT NULL AND h.assignee_ <> ?"
                + " AND NOT EXISTS (SELECT 1 FROM wf_rating r WHERE r.tenant_id = pt.tenant_id"
                + "   AND r.task_id = pt.task_id AND r.rater_id = ?)"
                + " ORDER BY h.end_time_ DESC LIMIT ?",
                tenant, raterId, raterId, Math.min(Math.max(limit, 1), 200));
    }

    public void touchPoolTask(String tenant, String taskId) {
        jdbc.update("UPDATE wf_pool_task SET updated_at = now() WHERE tenant_id = ? AND task_id = ?",
                tenant, taskId);
    }

    // ---------------- 日志 ----------------

    public void log(String tenant, String instanceId, String taskId, String nodeId, Long poolId,
                    String algorithm, Object candidates, Object scores, String chosenUserId,
                    String outcome, String reason) {
        jdbc.update("INSERT INTO wf_dispatch_log(tenant_id, instance_id, task_id, node_id, pool_id,"
                        + " algorithm, candidates, scores, chosen_user_id, outcome, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)",
                tenant, instanceId, taskId, nodeId, poolId, algorithm,
                write(candidates), write(scores), chosenUserId, outcome, reason);
    }

    /** 决策日志筛选条件：关键词匹配单号/任务名/节点/任务ID，可选结果、池、精确任务。 */
    public record LogQuery(String taskId, Long poolId, String keyword, String outcome) {
    }

    /** 统计筛选后的日志条数（用于分页 total）。 */
    public int countLogs(String tenant, LogQuery q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM wf_dispatch_log l"
                + " LEFT JOIN act_ru_task t ON t.id_ = l.task_id AND t.tenant_id_ = l.tenant_id"
                + " LEFT JOIN act_hi_taskinst ht ON ht.id_ = l.task_id AND ht.tenant_id_ = l.tenant_id"
                + " LEFT JOIN act_hi_procinst hp ON hp.id_ = l.instance_id AND hp.tenant_id_ = l.tenant_id"
                + " WHERE l.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        appendLogFilters(sql, args, q);
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 分页查询决策日志：按 id 倒序。 */
    public List<Map<String, Object>> logs(String tenant, LogQuery q, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT l.id, l.instance_id AS \"instanceId\","
                + " l.task_id AS \"taskId\", l.node_id AS \"nodeId\", l.pool_id AS \"poolId\", l.algorithm,"
                + " l.candidates::text AS \"candidates\", l.scores::text AS \"scores\","
                + " l.chosen_user_id AS \"chosenUserId\", l.outcome, l.reason, l.created_at AS \"createdAt\","
                + " COALESCE(ht.name_, t.name_, l.node_id) AS \"taskName\","
                + " hp.business_key_ AS \"businessKey\", pi.key_ AS \"processKey\""
                + " FROM wf_dispatch_log l"
                + " LEFT JOIN act_ru_task t ON t.id_ = l.task_id AND t.tenant_id_ = l.tenant_id"
                + " LEFT JOIN act_hi_taskinst ht ON ht.id_ = l.task_id AND ht.tenant_id_ = l.tenant_id"
                + " LEFT JOIN act_hi_procinst hp ON hp.id_ = l.instance_id AND hp.tenant_id_ = l.tenant_id"
                + " LEFT JOIN act_re_procdef pi ON pi.id_ = hp.proc_def_id_ AND pi.tenant_id_ = l.tenant_id"
                + " WHERE l.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        appendLogFilters(sql, args, q);
        sql.append(" ORDER BY l.id DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 200));
        args.add(Math.max(offset, 0));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private void appendLogFilters(StringBuilder sql, List<Object> args, LogQuery q) {
        if (q == null) {
            return;
        }
        if (q.taskId() != null && !q.taskId().isBlank()) {
            sql.append(" AND l.task_id = ?");
            args.add(q.taskId());
        }
        if (q.poolId() != null) {
            sql.append(" AND l.pool_id = ?");
            args.add(q.poolId());
        }
        if (q.outcome() != null && !q.outcome().isBlank()) {
            sql.append(" AND l.outcome = ?");
            args.add(q.outcome().toUpperCase());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String like = "%" + q.keyword().trim() + "%";
            sql.append(" AND (COALESCE(hp.business_key_, '') ILIKE ?"
                    + " OR COALESCE(ht.name_, t.name_, '') ILIKE ?"
                    + " OR COALESCE(l.node_id, '') ILIKE ?"
                    + " OR COALESCE(l.task_id, '') ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
    }

    // ---------------- 评价 ----------------
    public boolean hasRated(String tenant, String taskId, String raterId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wf_rating"
                + " WHERE tenant_id = ? AND task_id = ? AND rater_id = ?", Integer.class, tenant, taskId, raterId);
        return count != null && count > 0;
    }

    public void addRating(String tenant, String instanceId, String taskId, Long poolId,
                          String targetUserId, int score, Object tags, String comment, String raterId) {
        jdbc.update("INSERT INTO wf_rating(tenant_id, instance_id, task_id, pool_id, target_user_id,"
                        + " score, tags, comment, rater_id) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
                        + " ON CONFLICT (tenant_id, task_id, rater_id) DO NOTHING",
                tenant, instanceId, taskId, poolId, targetUserId, score, write(tags), comment, raterId);
    }

    public List<Map<String, Object>> ratings(String tenant, String targetUserId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, instance_id AS \"instanceId\","
                + " task_id AS \"taskId\", target_user_id AS \"targetUserId\", score,"
                + " tags::text AS \"tags\", comment, rater_id AS \"raterId\","
                + " created_at AS \"createdAt\" FROM wf_rating WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (targetUserId != null && !targetUserId.isBlank()) {
            sql.append(" AND target_user_id = ?");
            args.add(targetUserId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------------- 统计 ----------------

    public Map<String, Object> stats(String tenant, Long poolId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pools", count("SELECT COUNT(*) FROM wf_work_pool WHERE tenant_id = ?"
                + " AND status = 'ACTIVE'", tenant));
        result.put("checkedInToday", count("SELECT COUNT(*) FROM wf_checkin WHERE tenant_id = ?"
                + " AND biz_date = CURRENT_DATE AND checkout_at IS NULL", tenant));
        String poolFilter = poolId == null ? "" : " AND pool_id = " + poolId;
        result.put("openPoolTasks", count("SELECT COUNT(*) FROM wf_pool_task WHERE tenant_id = ?"
                + " AND status = 'OPEN'" + poolFilter, tenant));
        result.put("claimedToday", count("SELECT COUNT(*) FROM wf_pool_task WHERE tenant_id = ?"
                + " AND claimed_at >= CURRENT_DATE" + poolFilter, tenant));
        result.put("refusedToday", count("SELECT COUNT(*) FROM wf_dispatch_refusal WHERE tenant_id = ?"
                + " AND created_at >= CURRENT_DATE" + poolFilter, tenant));
        result.put("assignedToday", count("SELECT COUNT(*) FROM wf_dispatch_log WHERE tenant_id = ?"
                + " AND outcome IN ('ASSIGNED', 'POOLED') AND created_at >= CURRENT_DATE" + poolFilter, tenant));
        result.put("avgWaitSeconds", jdbc.queryForObject("SELECT COALESCE(ROUND(AVG(EXTRACT(EPOCH FROM"
                + " (claimed_at - created_at)))), 0) FROM wf_pool_task WHERE tenant_id = ?"
                + " AND claimed_at IS NOT NULL" + poolFilter, Integer.class, tenant));
        return result;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---------------- 工具 ----------------

    private Map<String, Object> json(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(value, Map.class);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = mapper.readValue(value, List.class);
            List<String> result = new ArrayList<>();
            for (Object item : raw) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException ex) {
            return "{}";
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static double decimal(BigDecimal value) {
        return value == null ? 0 : value.doubleValue();
    }
}
