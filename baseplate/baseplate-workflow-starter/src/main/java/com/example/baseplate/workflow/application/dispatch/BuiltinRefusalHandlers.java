package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.port.out.RefusalPolicyHandler;
import com.example.baseplate.workflow.adapter.out.persistence.AttendanceRepository;

import java.time.Instant;

/**
 * 内置拒绝策略。配置里只出现枚举 + 参数，不允许写表达式；
 * 业务方新增策略实现 RefusalPolicyHandler 并注册 Bean 即可。
 */
public final class BuiltinRefusalHandlers {

    private BuiltinRefusalHandlers() {
    }

    /** 本任务不再派给此人：事实已写 refusal 表，过滤器会排除，这里只做标记。 */
    public static RefusalPolicyHandler excludeTask() {
        return handler("EXCLUDE_TASK", (context, config) -> {
        });
    }

    public static RefusalPolicyHandler cooldown(AttendanceRepository attendance) {
        return handler("COOLDOWN", (context, config) -> {
            int minutes = integer(config.get("minutes"), 30);
            Long scope = "GLOBAL".equalsIgnoreCase(String.valueOf(config.get("scope")))
                    ? null : context.poolId();
            attendance.applyCooldown(context.tenantId(), context.userId(), scope,
                    context.now().plusSeconds(minutes * 60L),
                    "拒绝任务：" + context.reasonCode(), null);
        });
    }

    public static RefusalPolicyHandler block(AttendanceRepository attendance) {
        return handler("BLOCK", (context, config) -> {
            int days = integer(config.get("days"), 3);
            Long scope = "GLOBAL".equalsIgnoreCase(String.valueOf(config.get("scope")))
                    ? null : context.poolId();
            attendance.applyBlock(context.tenantId(), context.userId(), scope,
                    context.now().plusSeconds(days * 86400L),
                    "拒绝达到上限：" + context.reasonCode(), null);
        });
    }

    public static RefusalPolicyHandler weight(AttendanceRepository attendance) {
        return handler("WEIGHT", (context, config) -> {
            double multiplier = decimal(config.get("multiplier"), 0.8);
            int days = integer(config.get("days"), 7);
            Long scope = "GLOBAL".equalsIgnoreCase(String.valueOf(config.get("scope")))
                    ? null : context.poolId();
            attendance.applyWeight(context.tenantId(), context.userId(), scope, multiplier,
                    context.now().plusSeconds(days * 86400L),
                    "拒绝任务降权：" + context.reasonCode(), null);
        });
    }

    /** 通知类策略：事件已经发出，业务方订阅 DispatchLifecycleListener 去做站内信/IM。 */
    public static RefusalPolicyHandler notifyHandler() {
        return handler("NOTIFY", (context, config) -> {
        });
    }

    public static RefusalPolicyHandler escalate() {
        return handler("ESCALATE", (context, config) -> {
        });
    }

    private static RefusalPolicyHandler handler(String type, Apply apply) {
        return new RefusalPolicyHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void apply(com.example.baseplate.api.model.RefusalContext context,
                              java.util.Map<String, Object> config) {
                apply.apply(context, config == null ? java.util.Map.of() : config);
            }
        };
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    @FunctionalInterface
    private interface Apply {
        void apply(com.example.baseplate.api.model.RefusalContext context, java.util.Map<String, Object> config);
    }

    static Instant now() {
        return Instant.now();
    }
}
