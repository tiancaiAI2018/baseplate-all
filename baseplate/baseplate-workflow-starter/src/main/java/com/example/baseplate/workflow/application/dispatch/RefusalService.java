package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DispatchEvent;
import com.example.baseplate.api.model.RefusalContext;
import com.example.baseplate.api.port.out.DispatchLifecycleListener;
import com.example.baseplate.api.port.out.RefusalPolicyHandler;
import com.example.baseplate.api.port.out.RefusalReasonValidator;
import com.example.baseplate.workflow.adapter.out.persistence.AttendanceRepository;
import com.example.baseplate.workflow.adapter.out.persistence.DispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拒绝闭环：记录 → 执行可恢复惩罚 → 任务回池 → 事件。
 * 拒绝事实同时是下一次分单的过滤输入（已拒绝此人不再派同一单）。
 */
public class RefusalService {

    private static final Logger log = LoggerFactory.getLogger(RefusalService.class);

    private static final List<String> PENALTIES = List.of("COOLDOWN", "BLOCK", "WEIGHT", "SCORE");

    private final DispatchRepository dispatchRepository;
    private final AttendanceRepository attendance;
    private final WorkPoolResolver poolResolver;
    private final DispatchTaskGateway gateway;
    private final List<RefusalPolicyHandler> handlers;
    private final List<RefusalReasonValidator> validators;
    private final List<DispatchLifecycleListener> listeners;

    public RefusalService(DispatchRepository dispatchRepository, AttendanceRepository attendance,
                          WorkPoolResolver poolResolver, DispatchTaskGateway gateway,
                          List<RefusalPolicyHandler> handlers,
                          List<RefusalReasonValidator> validators,
                          List<DispatchLifecycleListener> listeners) {
        this.dispatchRepository = dispatchRepository;
        this.attendance = attendance;
        this.poolResolver = poolResolver;
        this.gateway = gateway;
        this.handlers = handlers == null ? List.of() : handlers;
        this.validators = validators == null ? List.of() : validators;
        this.listeners = listeners == null ? List.of() : listeners;
    }

    @Transactional
    public Map<String, Object> refuse(CurrentUser user, RefuseRequest request) {
        String tenant = user.tenantId();
        Map<String, Object> row = dispatchRepository.poolTaskByTaskId(tenant, request.taskId())
                .orElseThrow(() -> new IllegalArgumentException("该任务不是分单任务，不能走分单拒绝"));
        WorkPool pool = dispatchRepository.pool(tenant, asLong(row.get("poolId")))
                .orElseThrow(() -> new IllegalArgumentException("分单池不存在"));
        DispatchTaskGateway.TaskInfo info = gateway.taskInfo(request.taskId()).orElse(null);
        String type = request.type();
        requireOwnership(user, pool, info, row, type);

        if (attendance.hasAnyRefusal(tenant, request.taskId(), user.userId())) {
            Map<String, Object> duplicated = new LinkedHashMap<>();
            duplicated.put("duplicated", true);
            duplicated.put("message", "该任务已经拒绝过，不重复执行策略");
            return duplicated;
        }

        DispatchPolicy policy = info == null ? null : dispatchRepository.policy(tenant, info.processKey(),
                info.definitionVersion(), info.nodeId()).orElse(null);
        Map<String, Object> config = policy == null ? Map.of() : policy.section("refusal");
        int windowDays = integer(config.get("windowDays"), 7);
        int windowCount = attendance.refusalCount(tenant, user.userId(), windowDays);
        int consecutive = consecutiveOf(tenant, user.userId());

        RefusalContext context = new RefusalContext(tenant, pool.id(), pool.code(), request.taskId(),
                String.valueOf(row.get("instanceId")), user.userId(), type, request.reasonCode(),
                request.reasonText(), windowCount, consecutive, config, Instant.now());

        boolean penaltyAllowed = validators.stream().allMatch(validator -> validator.valid(context,
                request.reasonCode()));
        Map<String, Object> override = overrideOf(config, request.reasonCode());
        if (override != null && Boolean.TRUE.equals(override.get("skipPenalty"))) {
            penaltyAllowed = false;
        }

        long refusalId = attendance.addRefusal(tenant, pool.id(), request.taskId(),
                String.valueOf(row.get("instanceId")), user.userId(), type, request.reasonCode(),
                request.reasonText(), config, Map.of());
        attendance.markRefusal(tenant, user.userId());

        List<String> applied = new ArrayList<>();
        for (Map<String, Object> action : actionsFor(config, type)) {
            String actionType = String.valueOf(action.get("type"));
            if (!penaltyAllowed && PENALTIES.contains(actionType)) {
                continue;
            }
            for (RefusalPolicyHandler handler : handlers) {
                if (!handler.type().equalsIgnoreCase(actionType)) {
                    continue;
                }
                try {
                    handler.apply(context, action);
                    applied.add(actionType);
                } catch (RuntimeException ex) {
                    log.warn("拒绝策略执行失败 type={} user={}：{}", actionType, user.userId(), ex.getMessage());
                }
            }
        }

        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("applied", applied);
        effect.put("penaltyAllowed", penaltyAllowed);
        effect.put("windowCount", windowCount + 1);
        effect.put("consecutive", consecutive + 1);
        effect.put("escalate", shouldEscalate(config, consecutive + 1));
        attendance.updateRefusalEffect(tenant, refusalId, effect);

        if (!"OFFER_DISMISS".equals(type)) {
            gateway.unassign(request.taskId());
            dispatchRepository.reopenPoolTask(tenant, request.taskId());
        }

        publish(new DispatchEvent(DispatchEvent.Type.REFUSED, tenant,
                String.valueOf(row.get("instanceId")), request.taskId(),
                String.valueOf(row.get("nodeId")), pool.id(), user.userId(),
                request.reasonCode(), Instant.now(), effect));

        Map<String, Object> result = new LinkedHashMap<>(effect);
        result.put("refusalId", refusalId);
        result.put("refusalType", type);
        return result;
    }

    private void requireOwnership(CurrentUser user, WorkPool pool, DispatchTaskGateway.TaskInfo info,
                                  Map<String, Object> row, String type) {
        switch (type) {
            case "ASSIGN_REFUSAL" -> {
                String assignee = info == null ? null : info.assignee();
                if (!user.userId().equals(assignee)) {
                    throw new ForbiddenException("只能拒绝指派给自己的任务");
                }
            }
            case "RELEASE" -> {
                if (!user.userId().equals(String.valueOf(row.get("claimedBy")))) {
                    throw new ForbiddenException("只能退回自己认领的任务");
                }
            }
            case "OFFER_DISMISS" -> {
                if (!poolResolver.visible(user.tenantId(), pool, user.userId())) {
                    throw new ForbiddenException("该任务不在你所属组织的分单范围内");
                }
            }
            default -> throw new IllegalArgumentException("不支持的拒绝类型：" + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actionsFor(Map<String, Object> config, String type) {
        String key = switch (type) {
            case "RELEASE" -> "onRelease";
            case "OFFER_DISMISS" -> "onDismiss";
            default -> "onAssignRefusal";
        };
        Object raw = config.get(key);
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        // 基盘默认：划掉推荐只排除本单；拒绝指派/退回默认冷却 30 分钟。
        if ("OFFER_DISMISS".equals(type)) {
            return List.of(Map.of("type", "EXCLUDE_TASK"));
        }
        return List.of(Map.of("type", "EXCLUDE_TASK"),
                Map.of("type", "COOLDOWN", "minutes", 30, "scope", "POOL"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> overrideOf(Map<String, Object> config, String reasonCode) {
        Object raw = config.get("reasonOverrides");
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map
                    && reasonCode.equalsIgnoreCase(String.valueOf(map.get("reason")))) {
                return (Map<String, Object>) map;
            }
        }
        return null;
    }

    private static boolean shouldEscalate(Map<String, Object> config, int consecutive) {
        Object raw = config.get("escalate");
        if (!(raw instanceof Map<?, ?> map)) {
            return false;
        }
        int threshold = map.get("consecutive") instanceof Number number ? number.intValue() : 3;
        return consecutive >= threshold;
    }

    private int consecutiveOf(String tenant, String userId) {
        return attendance.agentState(tenant, userId)
                .map(state -> state.get("consecutiveRefusals"))
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .orElse(0);
    }

    private void publish(DispatchEvent event) {
        for (DispatchLifecycleListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                log.warn("拒绝事件监听失败 listener={}：{}", listener.getClass().getName(), ex.getMessage());
            }
        }
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
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
}
