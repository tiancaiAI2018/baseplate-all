package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.api.model.DispatchContext;
import com.example.baseplate.api.model.DispatchEvent;
import com.example.baseplate.api.port.out.DispatchLifecycleListener;
import com.example.baseplate.workflow.adapter.out.persistence.AttendanceRepository;
import com.example.baseplate.workflow.adapter.out.persistence.DispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分单核心：池解析 → 硬过滤 → 打分 → 指派/进池 → 日志 → 事件。
 *
 * 三条不能破的规则：
 * 1. 候选集只能来自池的组织范围，绝不查全量用户；
 * 2. 候选为空时进待派池，不回退发起人；
 * 3. 所有决策落 wf_dispatch_log，能回答「为什么派给他/为什么没派给他」。
 */
public class WorkflowDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDispatchService.class);

    private final DispatchRepository repository;
    private final AttendanceRepository attendance;
    private final WorkPoolResolver poolResolver;
    private final DispatchEvaluator evaluator;
    private final DispatchTaskGateway gateway;
    private final List<DispatchLifecycleListener> listeners;
    private final ZoneId zone;

    public WorkflowDispatchService(DispatchRepository repository, AttendanceRepository attendance,
                                   WorkPoolResolver poolResolver, DispatchEvaluator evaluator,
                                   DispatchTaskGateway gateway, List<DispatchLifecycleListener> listeners,
                                   ZoneId zone) {
        this.repository = repository;
        this.attendance = attendance;
        this.poolResolver = poolResolver;
        this.evaluator = evaluator;
        this.gateway = gateway;
        this.listeners = listeners == null ? List.of() : listeners;
        this.zone = zone == null ? ZoneId.of("Asia/Shanghai") : zone;
    }

    @Transactional
    public DispatchResult dispatch(DispatchRequest request) {
        Optional<DispatchPolicy> policyOpt = repository.policy(request.tenantId(), request.processKey(),
                request.definitionVersion(), request.nodeId());
        if (policyOpt.isEmpty()) {
            return DispatchResult.skipped();
        }
        DispatchPolicy policy = policyOpt.get();
        Optional<WorkPool> poolOpt = repository.pool(request.tenantId(), policy.poolId());
        if (poolOpt.isEmpty()) {
            return fail(request, policy, "分单池不存在");
        }
        WorkPool pool = poolOpt.get();
        if (!pool.active()) {
            return fail(request, policy, "分单池已停用");
        }
        List<Long> orgIds = poolResolver.orgIds(request.tenantId(), pool);
        if (orgIds.isEmpty()) {
            return fail(request, policy, "分单池未配置组织范围，拒绝派单");
        }

        List<DispatchCandidate> candidates = repository.candidates(request.tenantId(), orgIds,
                LocalDate.now(zone));
        DispatchContext context = new DispatchContext(request.tenantId(), request.processKey(),
                request.nodeId(), request.processInstanceId(), request.taskId(), pool.id(), pool.code(),
                request.variables(), policy.config(), Instant.now());
        List<DispatchEvaluator.Evaluated> evaluated = evaluator.filter(context, candidates);
        evaluated = evaluator.score(context, evaluated, weightsOf(policy));
        List<DispatchEvaluator.Evaluated> ranked = evaluator.ranked(evaluated);
        List<Map<String, Object>> explain = evaluator.explain(evaluated);

        if (ranked.isEmpty()) {
            String reason = candidates.isEmpty() ? "池内没有可用账号" : "全部候选人被硬过滤";
            savePoolTask(request, pool, "OPEN", null);
            repository.log(request.tenantId(), request.processInstanceId(), request.taskId(),
                    request.nodeId(), pool.id(), policy.algorithm(), explain, Map.of(), null,
                    DispatchResult.QUEUED, reason);
            publish(DispatchEvent.Type.FAILED, request, pool, null, reason);
            return DispatchResult.queued(pool.id(), pool.code(), reason);
        }

        if (!policy.autoEnabled()) {
            savePoolTask(request, pool, "OPEN", null);
            repository.log(request.tenantId(), request.processInstanceId(), request.taskId(),
                    request.nodeId(), pool.id(), policy.algorithm(), explain, scores(ranked), null,
                    DispatchResult.POOLED, "进入抢单池");
            publish(DispatchEvent.Type.POOLED, request, pool, null, "进入抢单池");
            return DispatchResult.pooled(pool.id(), pool.code());
        }

        DispatchCandidate chosen = ranked.get(0).candidate();
        try {
            gateway.assign(request.taskId(), chosen.userId());
        } catch (RuntimeException ex) {
            log.warn("分单指派失败 task={} user={}：{}", request.taskId(), chosen.userId(), ex.getMessage());
            savePoolTask(request, pool, "OPEN", null);
            repository.log(request.tenantId(), request.processInstanceId(), request.taskId(),
                    request.nodeId(), pool.id(), policy.algorithm(), explain, scores(ranked), null,
                    DispatchResult.QUEUED, "指派失败：" + ex.getMessage());
            publish(DispatchEvent.Type.FAILED, request, pool, null, "指派失败");
            return DispatchResult.queued(pool.id(), pool.code(), "指派失败：" + ex.getMessage());
        }

        savePoolTask(request, pool, "CLAIMED", chosen.userId());
        attendance.clearConsecutiveRefusals(request.tenantId(), chosen.userId());
        repository.log(request.tenantId(), request.processInstanceId(), request.taskId(),
                request.nodeId(), pool.id(), policy.algorithm(), explain, scores(ranked),
                chosen.userId(), DispatchResult.ASSIGNED, null);
        publish(DispatchEvent.Type.ASSIGNED, request, pool, chosen.userId(), null);
        return DispatchResult.assigned(pool.id(), pool.code(), chosen.userId());
    }

    /** 管理员强制指派：crossOrg 由 Controller 按权限决定，Service 只认布尔值。 */
    @Transactional
    public void manualAssign(CurrentUser user, String taskId, String toUserId, boolean crossOrg, String reason) {
        Map<String, Object> row = repository.poolTaskByTaskId(user.tenantId(), taskId)
                .orElseThrow(() -> new IllegalArgumentException("该任务不是分单任务"));
        WorkPool pool = repository.pool(user.tenantId(), asLong(row.get("poolId")))
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        if (!crossOrg && !poolResolver.visible(user.tenantId(), pool, toUserId)) {
            throw new ForbiddenException("目标用户不在该任务的组织范围内，跨组织指派需要单独权限");
        }
        gateway.assign(taskId, toUserId);
        String requesterId = row.get("requesterId") == null ? null : String.valueOf(row.get("requesterId"));
        repository.addPoolTask(user.tenantId(), pool.id(), taskId, String.valueOf(row.get("instanceId")),
                String.valueOf(row.get("nodeId")), requesterId,
                integerOf(row.get("priority")), "CLAIMED", toUserId);
        attendance.clearConsecutiveRefusals(user.tenantId(), toUserId);
        repository.log(user.tenantId(), String.valueOf(row.get("instanceId")), taskId,
                String.valueOf(row.get("nodeId")), pool.id(), "MANUAL", List.of(), Map.of(),
                toUserId, DispatchResult.ASSIGNED, reason);
        publish(DispatchEvent.Type.ASSIGNED, new DispatchRequest(user.tenantId(), null, 0,
                String.valueOf(row.get("nodeId")), String.valueOf(row.get("instanceId")), taskId,
                Map.of(), user.userId()), pool, toUserId, reason);
    }

    @Transactional
    public void claim(CurrentUser user, long poolTaskId) {
        Map<String, Object> row = repository.poolTask(user.tenantId(), poolTaskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或已关闭"));
        WorkPool pool = repository.pool(user.tenantId(), asLong(row.get("poolId")))
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        if (!pool.claimEnabled()) {
            throw new ForbiddenException("该分单池未开启抢单");
        }
        if (!poolResolver.visible(user.tenantId(), pool, user.userId())) {
            throw new ForbiddenException("该任务不在你所属组织的分单范围内");
        }
        String taskId = String.valueOf(row.get("taskId"));
        if (repository.claimPoolTask(user.tenantId(), poolTaskId, user.userId()) == 0) {
            throw new IllegalStateException("任务已被他人认领");
        }
        if (!gateway.claim(taskId, user.userId())) {
            throw new IllegalStateException("任务已被他人认领");
        }
        attendance.clearConsecutiveRefusals(user.tenantId(), user.userId());
        repository.log(user.tenantId(), String.valueOf(row.get("instanceId")), taskId,
                String.valueOf(row.get("nodeId")), pool.id(), "CLAIM", List.of(), Map.of(),
                user.userId(), "CLAIMED", null);
        publish(DispatchEvent.Type.CLAIMED, new DispatchRequest(user.tenantId(), null, 0,
                String.valueOf(row.get("nodeId")), String.valueOf(row.get("instanceId")), taskId,
                Map.of(), user.userId()), pool, user.userId(), null);
    }

    @Transactional
    public void backToPool(CurrentUser user, String taskId, String reason) {
        Map<String, Object> row = repository.poolTaskByTaskId(user.tenantId(), taskId)
                .orElseThrow(() -> new IllegalArgumentException("该任务不是分单任务"));
        WorkPool pool = repository.pool(user.tenantId(), asLong(row.get("poolId")))
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        gateway.unassign(taskId);
        repository.reopenPoolTask(user.tenantId(), taskId);
        repository.log(user.tenantId(), String.valueOf(row.get("instanceId")), taskId,
                String.valueOf(row.get("nodeId")), pool.id(), "MANUAL", List.of(), Map.of(),
                user.userId(), "REQUEUED", reason);
        publish(DispatchEvent.Type.REQUEUED, new DispatchRequest(user.tenantId(), null, 0,
                String.valueOf(row.get("nodeId")), String.valueOf(row.get("instanceId")), taskId,
                Map.of(), user.userId()), pool, user.userId(), reason);
    }

    /** 当前用户所在组织能看到的待抢任务。 */
    public List<Map<String, Object>> poolTasksFor(CurrentUser user) {
        List<WorkPool> visible = repository.pools(user.tenantId()).stream()
                .filter(WorkPool::active)
                .filter(pool -> poolResolver.visible(user.tenantId(), pool, user.userId()))
                .toList();
        List<Long> poolIds = visible.stream().map(WorkPool::id).toList();
        return repository.poolTasks(user.tenantId(), poolIds, "OPEN");
    }

    /** 决策日志分页查询，返回 { rows, total, page, size }。关键词/结果/池筛选在仓储层完成。 */
    public Map<String, Object> logs(String tenant, String taskId, Long poolId,
                                    String keyword, String outcome, int page, int size) {
        DispatchRepository.LogQuery query =
                new DispatchRepository.LogQuery(taskId, poolId, keyword, outcome);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        int total = repository.countLogs(tenant, query);
        List<Map<String, Object>> rows = repository.logs(tenant, query, safeSize, (safePage - 1) * safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    public List<Map<String, Object>> ratings(String tenant, String userId, int limit) {
        return repository.ratings(tenant, userId, limit);
    }

    public Map<String, Object> stats(String tenant, Long poolId) {
        return repository.stats(tenant, poolId);
    }

    @Transactional
    public void rate(CurrentUser user, String taskId, int score, List<String> tags, String comment) {
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException("评分必须在 1~5 之间");
        }
        if (!gateway.finished(taskId)) {
            throw new IllegalArgumentException("任务尚未办结，不能评价");
        }
        String target = gateway.historicAssignee(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务没有办理人"));
        if (target.equals(user.userId())) {
            throw new IllegalArgumentException("不能给自己评价");
        }
        if (repository.hasRated(user.tenantId(), taskId, user.userId())) {
            throw new IllegalStateException("该任务已经评价过");
        }
        Map<String, Object> row = repository.poolTaskByTaskId(user.tenantId(), taskId).orElse(Map.of());
        Long poolId = row.get("poolId") == null ? null : asLong(row.get("poolId"));
        repository.addRating(user.tenantId(), String.valueOf(row.getOrDefault("instanceId", "")),
                taskId, poolId, target, score, tags == null ? List.of() : tags, comment, user.userId());
        attendance.updateRating(user.tenantId(), target, score);
        dispatchEvent(DispatchEvent.Type.CLAIMED, user.tenantId(),
                String.valueOf(row.getOrDefault("instanceId", "")), taskId, null, poolId,
                target, "RATED:" + score, Map.of("score", score));
    }

    private void savePoolTask(DispatchRequest request, WorkPool pool, String status, String claimedBy) {
        repository.addPoolTask(request.tenantId(), pool.id(), request.taskId(), request.processInstanceId(),
                request.nodeId(), request.requesterId(), priority(request.variables()), status, claimedBy);
    }

    private DispatchResult fail(DispatchRequest request, DispatchPolicy policy, String reason) {
        repository.log(request.tenantId(), request.processInstanceId(), request.taskId(), request.nodeId(),
                policy.poolId(), policy.algorithm(), List.of(), Map.of(), null,
                DispatchResult.FAILED, reason);
        dispatchEvent(DispatchEvent.Type.FAILED, request.tenantId(), request.processInstanceId(),
                request.taskId(), request.nodeId(), policy.poolId(), null, reason, Map.of());
        return DispatchResult.failed(policy.poolId(), null, reason);
    }

    private void publish(DispatchEvent.Type type, DispatchRequest request, WorkPool pool,
                         String userId, String reason) {
        dispatchEvent(type, request.tenantId(), request.processInstanceId(), request.taskId(),
                request.nodeId(), pool.id(), userId, reason,
                Map.of("poolCode", pool.code(), "instanceId", request.processInstanceId()));
    }

    private void dispatchEvent(DispatchEvent.Type type, String tenant, String instanceId, String taskId,
                               String nodeId, Long poolId, String userId, String reason,
                               Map<String, Object> payload) {
        DispatchEvent event = new DispatchEvent(type, tenant, instanceId, taskId, nodeId, poolId,
                userId, reason, Instant.now(), payload);
        for (DispatchLifecycleListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                log.warn("分单事件监听失败 type={} listener={}：{}", type,
                        listener.getClass().getName(), ex.getMessage());
            }
        }
    }

    private static Map<String, Double> scores(List<DispatchEvaluator.Evaluated> ranked) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (DispatchEvaluator.Evaluated item : ranked) {
            result.put(item.candidate().userId(), item.score());
        }
        return result;
    }

    /**
     * 配置了 weights 就按配置；没有配置时把「主算法」提到 0.6，其余四项均分。
     * 这样算法不是展示字段，改算法确实会改变排序。
     */
    private static Map<String, Object> weightsOf(DispatchPolicy policy) {
        Map<String, Object> configured = new LinkedHashMap<>(policy.section("weights"));
        if (!configured.isEmpty()) {
            return configured;
        }
        String primary = policy.algorithm() == null ? "LEAST_LOAD" : policy.algorithm().toUpperCase();
        Map<String, Object> weights = new LinkedHashMap<>();
        for (String name : List.of("LEAST_LOAD", "RATING", "FAIR", "SKILL", "WEIGHT")) {
            weights.put(name, name.equals(primary) ? 0.6 : 0.1);
        }
        return weights;
    }

    private static int priority(Map<String, Object> variables) {
        Object value = variables.get("priority");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int integerOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 给超时扫描复用：把已指派的 MIXED 任务放回池里。 */
    @Transactional
    public void releaseToPool(String tenant, String taskId, String reason) {
        Map<String, Object> row = repository.poolTaskByTaskId(tenant, taskId).orElse(null);
        if (row == null) {
            return;
        }
        Long poolId = asLong(row.get("poolId"));
        String instanceId = String.valueOf(row.get("instanceId"));
        String nodeId = String.valueOf(row.get("nodeId"));
        try {
            gateway.unassign(taskId);
        } catch (RuntimeException ex) {
            // 任务已办结/已删除：关闭池记录，不再参与扫描
            repository.closePoolTaskByTask(tenant, taskId, "CLOSED");
            dispatchEvent(DispatchEvent.Type.TIMEOUT, tenant, instanceId, taskId, nodeId,
                    poolId, null, "任务已结束，关闭池记录", Map.of());
            return;
        }
        repository.reopenPoolTask(tenant, taskId);
        repository.touchPoolTask(tenant, taskId);
        dispatchEvent(DispatchEvent.Type.TIMEOUT, tenant, instanceId, taskId, nodeId,
                poolId, String.valueOf(row.get("claimedBy")), reason, Map.of());
    }

    /**
     * 待派任务超时重试：按当前策略重新评估一次候选。
     * 仍派不出去就发 TIMEOUT 事件让业务方升级通知，不回退发起人。
     */
    @Transactional
    public DispatchResult redispatch(String tenant, String taskId) {
        Map<String, Object> row = repository.poolTaskByTaskId(tenant, taskId).orElse(null);
        if (row == null) {
            return DispatchResult.skipped();
        }
        DispatchTaskGateway.TaskInfo info = gateway.taskInfo(taskId).orElse(null);
        if (info == null) {
            repository.closePoolTaskByTask(tenant, taskId, "CLOSED");
            return DispatchResult.skipped();
        }
        if (info.assignee() != null && !info.assignee().isBlank()) {
            return DispatchResult.assigned(asLong(row.get("poolId")), null, info.assignee());
        }
        DispatchRequest request = new DispatchRequest(tenant, info.processKey(),
                info.definitionVersion(), info.nodeId(), info.instanceId(), taskId, info.variables(),
                String.valueOf(row.getOrDefault("requesterId", "")));
        DispatchResult result = dispatch(request);
        if (!DispatchResult.ASSIGNED.equals(result.outcome())) {
            dispatchEvent(DispatchEvent.Type.TIMEOUT, tenant, info.instanceId(), taskId, info.nodeId(),
                    asLong(row.get("poolId")), null, "待派超时升级",
                    Map.<String, Object>of("outcome", result.outcome()));
        }
        return result;
    }

    /**
     * 坐席上线后重派：只处理该用户组织可见的活跃池里仍处于 OPEN 的任务。
     * 每个任务复用 redispatch（内部检查 assignee、幂等），多人同时上线也不会重复派发。
     * 新事务里逐条处理，单条失败不影响其它任务，更不影响签到主流程。
     *
     * @return 本轮成功触发重派的任务数
     */
    public int redispatchForUser(String tenant, String userId) {
        List<Long> poolIds = repository.pools(tenant).stream()
                .filter(WorkPool::active)
                .filter(pool -> poolResolver.visible(tenant, pool, userId))
                .map(WorkPool::id)
                .toList();
        if (poolIds.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> open = repository.poolTasks(tenant, poolIds, "OPEN");
        int handled = 0;
        for (Map<String, Object> row : open) {
            String taskId = String.valueOf(row.get("taskId"));
            try {
                redispatch(tenant, taskId);
                handled++;
            } catch (RuntimeException ex) {
                log.warn("坐席上线重派失败 tenant={} task={}：{}", tenant, taskId, ex.getMessage());
            }
        }
        return handled;
    }

    public List<DispatchCandidate> candidatesFor(String tenant, long poolId) {
        WorkPool pool = repository.pool(tenant, poolId).orElse(null);
        if (pool == null) {
            return List.of();
        }
        List<Long> orgIds = poolResolver.orgIds(tenant, pool);
        return repository.candidates(tenant, orgIds, LocalDate.now(zone));
    }

    public List<Long> poolOrgIds(String tenant, WorkPool pool) {
        return new ArrayList<>(poolResolver.orgIds(tenant, pool));
    }
}
