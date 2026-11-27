package com.example.baseplate.workflow.autoconfigure;

import com.example.baseplate.workflow.application.dispatch.DispatchTimeoutService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 接单超时扫描。默认每分钟一次，多实例部署也安全（回池是幂等 UPDATE）。
 * 关闭：baseplate.workflow.dispatch.timeout-scan-enabled=false。
 */
@Component
@ConditionalOnProperty(name = "baseplate.workflow.dispatch.timeout-scan-enabled",
        havingValue = "true", matchIfMissing = true)
public class DispatchTimeoutScheduler {

    private final DispatchTimeoutService timeoutService;

    public DispatchTimeoutScheduler(DispatchTimeoutService timeoutService) {
        this.timeoutService = timeoutService;
    }

    @Scheduled(fixedDelayString = "${baseplate.workflow.dispatch.scan-interval-ms:60000}")
    public void scan() {
        timeoutService.scan();
    }
}
