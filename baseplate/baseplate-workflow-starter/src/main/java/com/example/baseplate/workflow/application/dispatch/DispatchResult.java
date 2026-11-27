package com.example.baseplate.workflow.application.dispatch;

/** 分单结果。outcome 与 wf_dispatch_log.outcome 对齐，便于看板直接统计。 */
public record DispatchResult(
        String outcome,
        String userId,
        Long poolId,
        String poolCode,
        String reason) {

    public static final String ASSIGNED = "ASSIGNED";
    public static final String POOLED = "POOLED";
    public static final String QUEUED = "QUEUED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public static DispatchResult skipped() {
        return new DispatchResult(SKIPPED, null, null, null, "节点未配置分单策略");
    }

    public static DispatchResult failed(Long poolId, String poolCode, String reason) {
        return new DispatchResult(FAILED, null, poolId, poolCode, reason);
    }

    public static DispatchResult queued(Long poolId, String poolCode, String reason) {
        return new DispatchResult(QUEUED, null, poolId, poolCode, reason);
    }

    public static DispatchResult assigned(Long poolId, String poolCode, String userId) {
        return new DispatchResult(ASSIGNED, userId, poolId, poolCode, null);
    }

    public static DispatchResult pooled(Long poolId, String poolCode) {
        return new DispatchResult(POOLED, null, poolId, poolCode, "进入抢单池");
    }
}
