package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.workflow.adapter.out.persistence.DispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 超时扫描，两件事：
 * 1. 待派池里的任务长时间没人抢 → 重新按策略派一次，仍失败发 TIMEOUT 事件升级；
 * 2. 已指派但长时间没办理 → 收回池里重新流转（可配置关闭）。
 *
 * 多实例并发安全：回池是幂等 UPDATE，重派会先检查 assignee。
 */
public final class DispatchTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(DispatchTimeoutService.class);

    private final DispatchRepository repository;
    private final WorkflowDispatchService dispatchService;
    private final int pendingTimeoutMinutes;
    private final int handleTimeoutMinutes;

    public DispatchTimeoutService(DispatchRepository repository, WorkflowDispatchService dispatchService,
                                  int pendingTimeoutMinutes, int handleTimeoutMinutes) {
        this.repository = repository;
        this.dispatchService = dispatchService;
        this.pendingTimeoutMinutes = pendingTimeoutMinutes <= 0 ? 30 : pendingTimeoutMinutes;
        this.handleTimeoutMinutes = handleTimeoutMinutes;
    }

    /** 返回本轮处理（重派 + 回池）的任务数。 */
    public int scan() {
        int handled = retryPending();
        if (handleTimeoutMinutes > 0) {
            handled += recycleClaimed();
        }
        return handled;
    }

    private int retryPending() {
        List<Map<String, Object>> expired = repository.expiredPoolTasks(pendingTimeoutMinutes);
        int handled = 0;
        for (Map<String, Object> row : expired) {
            String tenant = String.valueOf(row.get("tenantId"));
            String taskId = String.valueOf(row.get("taskId"));
            try {
                dispatchService.redispatch(tenant, taskId);
                handled++;
            } catch (RuntimeException ex) {
                log.warn("待派任务超时重派失败 tenant={} task={}：{}", tenant, taskId, ex.getMessage());
                repository.touchPoolTask(tenant, taskId);
            }
        }
        return handled;
    }

    private int recycleClaimed() {
        List<Map<String, Object>> expired = repository.expiredClaimedTasks(handleTimeoutMinutes);
        int handled = 0;
        for (Map<String, Object> row : expired) {
            String tenant = String.valueOf(row.get("tenantId"));
            String taskId = String.valueOf(row.get("taskId"));
            try {
                dispatchService.releaseToPool(tenant, taskId, "办理超时自动回池");
                handled++;
            } catch (RuntimeException ex) {
                log.warn("办理超时回池失败 tenant={} task={}：{}", tenant, taskId, ex.getMessage());
            }
        }
        return handled;
    }
}
