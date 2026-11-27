package com.example.baseplate.workflow.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.workflow.adapter.out.persistence.AttendanceRepository;
import com.example.baseplate.workflow.adapter.out.persistence.DispatchRepository;
import com.example.baseplate.workflow.application.dispatch.CheckinService;
import com.example.baseplate.workflow.application.dispatch.DispatchTimeoutService;
import com.example.baseplate.workflow.application.dispatch.RefusalService;
import com.example.baseplate.workflow.application.dispatch.RefuseRequest;
import com.example.baseplate.workflow.application.dispatch.WorkPoolOrg;
import com.example.baseplate.workflow.application.dispatch.WorkflowDispatchService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分单中心：签到、池配置、策略、抢单、指派、拒绝、评价、看板。
 * 权限码与 baseplate-module.yml 逐字符一致，拼错会在运行期 403。
 */
@RestController
@RequestMapping("/api/workflow/dispatch")
public class DispatchController {

    private final CheckinService checkinService;
    private final WorkflowDispatchService dispatchService;
    private final RefusalService refusalService;
    private final DispatchTimeoutService timeoutService;
    private final DispatchRepository dispatchRepository;
    private final AttendanceRepository attendanceRepository;
    private final CurrentUserProvider currentUser;

    public DispatchController(CheckinService checkinService, WorkflowDispatchService dispatchService,
                              RefusalService refusalService, DispatchTimeoutService timeoutService,
                              DispatchRepository dispatchRepository,
                              AttendanceRepository attendanceRepository,
                              CurrentUserProvider currentUser) {
        this.checkinService = checkinService;
        this.dispatchService = dispatchService;
        this.refusalService = refusalService;
        this.timeoutService = timeoutService;
        this.dispatchRepository = dispatchRepository;
        this.attendanceRepository = attendanceRepository;
        this.currentUser = currentUser;
    }

    // ---------------- 签到 ----------------

    @GetMapping("/checkin/today")
    @RequiresPermission({"wf:checkin", "wf:task"})
    public Map<String, Object> today() {
        return checkinService.today(user());
    }

    @PostMapping("/checkin")
    @RequiresPermission({"wf:checkin", "wf:task"})
    @AuditLog(action = "wf:checkin", resource = "wf_checkin")
    public Map<String, Object> checkin() {
        return checkinService.checkin(user());
    }

    @PostMapping("/checkin/checkout")
    @RequiresPermission({"wf:checkin", "wf:task"})
    @AuditLog(action = "wf:checkout", resource = "wf_checkin")
    public Map<String, Object> checkout() {
        return checkinService.checkout(user());
    }

    @PostMapping("/checkin/heartbeat")
    @RequiresPermission({"wf:checkin", "wf:task"})
    public Map<String, Object> heartbeat(@RequestParam(required = false) String status) {
        return checkinService.heartbeat(user(), status);
    }

    // ---------------- 池管理 ----------------

    @GetMapping("/pools")
    @RequiresPermission("wf:pool")
    public List<Map<String, Object>> pools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var pool : dispatchRepository.pools(tenant())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pool.id());
            row.put("code", pool.code());
            row.put("name", pool.name());
            row.put("description", pool.description());
            row.put("status", pool.status());
            row.put("claimEnabled", pool.claimEnabled());
            row.put("orgs", pool.orgs());
            result.add(row);
        }
        return result;
    }

    @PostMapping("/pools")
    @RequiresPermission("wf:pool")
    @AuditLog(action = "wf:save-pool", resource = "wf_work_pool")
    public Map<String, Object> savePool(@RequestBody PoolRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new IllegalArgumentException("池编码不能为空");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("池名称不能为空");
        }
        List<WorkPoolOrg> orgs = new ArrayList<>();
        if (request.orgs() != null) {
            for (PoolOrgRequest org : request.orgs()) {
                if (org.orgId() != null) {
                    orgs.add(new WorkPoolOrg(org.orgId(), Boolean.TRUE.equals(org.includeChildren())));
                }
            }
        }
        long id = dispatchRepository.savePool(tenant(), request.code().trim(), request.name(),
                request.description(), request.status() == null ? "ACTIVE" : request.status(),
                request.claimEnabled() == null || request.claimEnabled(), user().userId());
        dispatchRepository.savePoolOrgs(tenant(), id, orgs);
        return Map.of("id", id, "code", request.code(), "orgs", orgs.size());
    }

    @PutMapping("/pools/{id}")
    @RequiresPermission("wf:pool")
    @AuditLog(action = "wf:save-pool", resource = "wf_work_pool")
    public Map<String, Object> updatePool(@PathVariable long id, @RequestBody PoolRequest request) {
        var existing = dispatchRepository.pool(tenant(), id)
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        long saved = dispatchRepository.savePool(tenant(), existing.code(),
                request.name() == null ? existing.name() : request.name(),
                request.description() == null ? existing.description() : request.description(),
                request.status() == null ? existing.status() : request.status(),
                request.claimEnabled() == null ? existing.claimEnabled() : request.claimEnabled(),
                user().userId());
        if (request.orgs() != null) {
            List<WorkPoolOrg> orgs = new ArrayList<>();
            for (PoolOrgRequest org : request.orgs()) {
                if (org.orgId() != null) {
                    orgs.add(new WorkPoolOrg(org.orgId(), Boolean.TRUE.equals(org.includeChildren())));
                }
            }
            dispatchRepository.savePoolOrgs(tenant(), saved, orgs);
        }
        return Map.of("id", saved);
    }

    @DeleteMapping("/pools/{id}")
    @RequiresPermission("wf:pool")
    @AuditLog(action = "wf:suspend-pool", resource = "wf_work_pool")
    public Map<String, Object> suspendPool(@PathVariable long id) {
        var existing = dispatchRepository.pool(tenant(), id)
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        dispatchRepository.savePool(tenant(), existing.code(), existing.name(), existing.description(),
                "SUSPENDED", existing.claimEnabled(), user().userId());
        return Map.of("id", id, "status", "SUSPENDED");
    }

    @GetMapping("/orgs")
    @RequiresPermission("wf:pool")
    public List<Map<String, Object>> orgs() {
        return dispatchRepository.orgs(tenant());
    }

    @GetMapping("/assignable-users")
    @RequiresPermission("wf:pool")
    public List<Map<String, Object>> assignableUsers(@RequestParam long poolId) {
        var pool = dispatchRepository.pool(tenant(), poolId)
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        return dispatchRepository.usersOfOrgIds(tenant(), dispatchService.poolOrgIds(tenant(), pool));
    }

    // ---------------- 策略 ----------------

    /** 设计器用：只给可选项，不暴露其它租户的池数据。 */
    @GetMapping("/options")
    @RequiresPermission("wf:view")
    public Map<String, Object> options() {
        List<Map<String, Object>> pools = new ArrayList<>();
        for (var pool : dispatchRepository.pools(tenant())) {
            if (!pool.active()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pool.id());
            row.put("code", pool.code());
            row.put("name", pool.name());
            row.put("claimEnabled", pool.claimEnabled());
            pools.add(row);
        }
        return Map.of(
                "pools", pools,
                "modes", List.of(
                        Map.of("value", "AUTO", "label", "自动派单"),
                        Map.of("value", "CLAIM", "label", "抢单"),
                        Map.of("value", "MIXED", "label", "自动优先 + 兜底抢单")),
                "algorithms", List.of("LEAST_LOAD", "RATING", "FAIR", "SKILL", "WEIGHT"),
                "filters", List.of("CheckinFilter", "CooldownFilter", "CapacityFilter",
                        "OnlineFilter", "RefusalExclusionFilter"),
                "refusalReasons", List.of(
                        Map.of("value", "BUSY", "label", "当前任务过多"),
                        Map.of("value", "SKILL_MISMATCH", "label", "技能不匹配"),
                        Map.of("value", "OUT_OF_SCOPE", "label", "不在职责范围"),
                        Map.of("value", "CONFLICT", "label", "存在利益冲突"),
                        Map.of("value", "OTHER", "label", "其他（需填写说明）")));
    }

    @GetMapping("/policies")
    @RequiresPermission("wf:view")
    public List<Map<String, Object>> policies(@RequestParam String processKey) {
        return dispatchRepository.policies(tenant(), processKey);
    }

    @PutMapping("/policies")
    @RequiresPermission("wf:config")
    @AuditLog(action = "wf:save-dispatch-policy", resource = "wf_dispatch_policy")
    public Map<String, Object> savePolicy(@RequestBody PolicyRequest request) {
        if (request.processKey() == null || request.nodeId() == null || request.poolId() == null) {
            throw new IllegalArgumentException("processKey / nodeId / poolId 不能为空");
        }
        dispatchRepository.savePolicy(tenant(), request.processKey(),
                request.definitionVersion() == null ? 0 : request.definitionVersion(),
                request.nodeId(), request.poolId(),
                request.mode() == null ? "AUTO" : request.mode(),
                request.algorithm() == null ? "LEAST_LOAD" : request.algorithm(),
                request.config() == null ? Map.of() : request.config(), user().userId());
        return Map.of("saved", true);
    }

    // ---------------- 抢单与指派 ----------------

    @GetMapping("/pool-tasks")
    @RequiresPermission("wf:task")
    public List<Map<String, Object>> poolTasks() {
        return dispatchService.poolTasksFor(user());
    }

    @GetMapping("/my-tasks")
    @RequiresPermission("wf:task")
    public List<Map<String, Object>> myTasks() {
        return dispatchRepository.myTasks(tenant(), user().userId());
    }

    @PostMapping("/pool-tasks/{id}/claim")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:claim-task", resource = "wf_pool_task")
    public Map<String, Object> claim(@PathVariable long id) {
        dispatchService.claim(user(), id);
        return Map.of("claimed", true);
    }

    @PostMapping("/tasks/{taskId}/assign")
    @RequiresPermission("wf:dispatch")
    @AuditLog(action = "wf:assign-task", resource = "wf_pool_task")
    public Map<String, Object> assign(@PathVariable String taskId, @RequestBody AssignRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("请选择要指派的用户");
        }
        boolean crossOrg = Boolean.TRUE.equals(request.crossOrg())
                && user().permissions().contains("wf:dispatch:cross-org");
        dispatchService.manualAssign(user(), taskId, request.userId(), crossOrg, request.reason());
        return Map.of("assigned", true, "userId", request.userId());
    }

    @PostMapping("/tasks/{taskId}/back-to-pool")
    @RequiresPermission("wf:dispatch")
    @AuditLog(action = "wf:back-to-pool", resource = "wf_pool_task")
    public Map<String, Object> backToPool(@PathVariable String taskId,
                                          @RequestBody(required = false) BackRequest request) {
        dispatchService.backToPool(user(), taskId, request == null ? null : request.reason());
        return Map.of("requeued", true);
    }

    @PostMapping("/tasks/{taskId}/refuse")
    @RequiresPermission("wf:task")
    @AuditLog(action = "wf:refuse-task", resource = "wf_dispatch_refusal")
    public Map<String, Object> refuse(@PathVariable String taskId, @RequestBody RefuseRequest request) {
        return refusalService.refuse(user(), new RefuseRequest(taskId, request.type(),
                request.reasonCode(), request.reasonText()));
    }

    // ---------------- 拒绝与惩罚 ----------------

    @GetMapping("/refusals")
    @RequiresPermission("wf:view")
    public List<Map<String, Object>> refusals(@RequestParam(required = false) String userId,
                                              @RequestParam(required = false) Long poolId,
                                              @RequestParam(defaultValue = "100") int limit) {
        return attendanceRepository.refusals(tenant(), scopedUserId(userId), poolId, limit);
    }

    @GetMapping("/penalties")
    @RequiresPermission("wf:view")
    public List<Map<String, Object>> penalties(@RequestParam(required = false) String userId,
                                               @RequestParam(defaultValue = "100") int limit) {
        return attendanceRepository.penalties(tenant(), scopedUserId(userId), limit);
    }

    @PostMapping("/penalties/{id}/revoke")
    @RequiresPermission("wf:dispatch:revoke")
    @AuditLog(action = "wf:revoke-penalty", resource = "wf_agent_penalty")
    public Map<String, Object> revoke(@PathVariable long id) {
        var penalty = attendanceRepository.penalty(tenant(), id).orElse(null);
        boolean revoked = attendanceRepository.revokePenalty(tenant(), id, user().userId());
        if (penalty != null) {
            String userId = String.valueOf(penalty.get("userId"));
            double weight = attendanceRepository.effectiveWeight(tenant(), userId, null);
            attendanceRepository.resetWeight(tenant(), userId, weight);
        }
        return Map.of("revoked", revoked);
    }

    // ---------------- 评价与看板 ----------------

    @PostMapping("/tasks/{taskId}/rate")
    @RequiresPermission("wf:rating")
    @AuditLog(action = "wf:rate-task", resource = "wf_rating")
    public Map<String, Object> rate(@PathVariable String taskId, @RequestBody RateRequest request) {
        dispatchService.rate(user(), taskId, request.score() == null ? 5 : request.score(),
                request.tags(), request.comment());
        return Map.of("rated", true);
    }

    @GetMapping("/ratings")
    @RequiresPermission("wf:view")
    public List<Map<String, Object>> ratings(@RequestParam(required = false) String userId,
                                             @RequestParam(defaultValue = "100") int limit) {
        return dispatchService.ratings(tenant(), scopedUserId(userId), limit);
    }

    @GetMapping("/ratable-tasks")
    @RequiresPermission("wf:task")
    public List<Map<String, Object>> ratableTasks(@RequestParam(defaultValue = "50") int limit) {
        return dispatchRepository.ratableTasks(tenant(), user().userId(), limit);
    }

    @GetMapping("/stats")
    @RequiresPermission("wf:view")
    public Map<String, Object> stats(@RequestParam(required = false) Long poolId) {
        Map<String, Object> result = new LinkedHashMap<>(dispatchService.stats(tenant(), poolId));
        result.put("refusal", attendanceRepository.refusalStats(tenant(), 7));
        return result;
    }

    @GetMapping("/logs")
    @RequiresPermission("wf:view")
    public Map<String, Object> logs(@RequestParam(required = false) String taskId,
                                    @RequestParam(required = false) Long poolId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String outcome,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return dispatchService.logs(tenant(), taskId, poolId, keyword, outcome, page, size);
    }

    @PostMapping("/timeout-scan")
    @RequiresPermission("wf:manage")
    public Map<String, Object> timeoutScan() {
        return Map.of("released", timeoutService.scan());
    }

    // ---------------- 工具 ----------------

    private CurrentUser user() {
        CurrentUser user = currentUser.current();
        if (user == null) {
            throw new ForbiddenException("未登录");
        }
        return user;
    }

    private String tenant() {
        return user().tenantId() == null || user().tenantId().isBlank() ? "default" : user().tenantId();
    }

    /** 普通用户只能看自己的拒绝/惩罚/评价；有 wf:dispatch 的管理者可以查别人。 */
    private String scopedUserId(String requested) {
        if (requested == null || requested.isBlank()) {
            return user().userId();
        }
        if (!requested.equals(user().userId()) && !user().permissions().contains("wf:dispatch")) {
            throw new ForbiddenException("没有查看他人分单记录的权限");
        }
        return requested;
    }

    public record PoolRequest(String code, String name, String description, String status,
                              Boolean claimEnabled, List<PoolOrgRequest> orgs) {
    }

    public record PoolOrgRequest(Long orgId, Boolean includeChildren) {
    }

    public record PolicyRequest(String processKey, Integer definitionVersion, String nodeId,
                                Long poolId, String mode, String algorithm,
                                Map<String, Object> config) {
    }

    public record AssignRequest(String userId, Boolean crossOrg, String reason) {
    }

    public record BackRequest(String reason) {
    }

    public record RateRequest(Integer score, List<String> tags, String comment) {
    }
}
