package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.api.model.DispatchContext;
import com.example.baseplate.api.model.DispatchFilterResult;
import com.example.baseplate.api.port.out.DispatchFilter;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BiFunction;

/**
 * 内置硬过滤。签到和冷却默认开启，业务方可以按池策略关掉签到，
 * 但「已拒绝此单」和「容量已满」不允许关——关掉会产生重复派单和无限压榨。
 */
public final class BuiltinDispatchFilters {

    private BuiltinDispatchFilters() {
    }

    public static DispatchFilter checkin() {
        return named("CheckinFilter", (context, candidate) -> {
            if (!context.flag("requireCheckin", true)) {
                return DispatchFilterResult.pass();
            }
            return candidate.checkedIn() ? DispatchFilterResult.pass()
                    : DispatchFilterResult.reject("今日未签到或已签退");
        });
    }

    public static DispatchFilter cooldown() {
        return named("CooldownFilter", (context, candidate) -> {
            if (context.flag("ignoreCooldown", false)) {
                return DispatchFilterResult.pass();
            }
            return candidate.cooling(context.now()) ? DispatchFilterResult.reject("拒绝冷却中")
                    : DispatchFilterResult.pass();
        });
    }

    public static DispatchFilter capacity() {
        return named("CapacityFilter", (context, candidate) -> {
            if (!candidate.capacityLimited()) {
                return DispatchFilterResult.pass();
            }
            return candidate.activeCount() >= candidate.capacity()
                    ? DispatchFilterResult.reject("在途任务已达上限") : DispatchFilterResult.pass();
        });
    }

    public static DispatchFilter online() {
        return named("OnlineFilter", (context, candidate) -> {
            if (!context.flag("requireOnline", false)) {
                return DispatchFilterResult.pass();
            }
            // 心跳超过 3 分钟视为离线；签到只决定资格，在线决定能不能实时推送
            Instant heartbeat = candidate.lastHeartbeat();
            if (heartbeat == null || Duration.between(heartbeat, context.now()).toMinutes() > 3) {
                return DispatchFilterResult.reject("不在线（心跳超时）");
            }
            return DispatchFilterResult.pass();
        });
    }

    public static DispatchFilter refused(DispatchRepositoryGateway gateway) {
        return named("RefusalExclusionFilter", (context, candidate) -> {
            if (context.taskId() == null) {
                return DispatchFilterResult.pass();
            }
            return gateway.refused(context.tenantId(), context.taskId(), candidate.userId())
                    ? DispatchFilterResult.reject("已拒绝过此任务") : DispatchFilterResult.pass();
        });
    }

    /** 只依赖一个方法，避免过滤器把整个 Repository 拖进契约层。 */
    @FunctionalInterface
    public interface DispatchRepositoryGateway {
        boolean refused(String tenant, String taskId, String userId);
    }

    private static DispatchFilter named(String name, BiFunction<DispatchContext, DispatchCandidate,
            DispatchFilterResult> body) {
        return new DispatchFilter() {
            @Override
            public DispatchFilterResult test(DispatchContext context, DispatchCandidate candidate) {
                return body.apply(context, candidate);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
