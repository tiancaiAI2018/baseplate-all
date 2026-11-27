package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.model.AgentOnlineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 坐席上线（签到 / 心跳转 ONLINE）后，在事务提交时重派该用户可见池里的待派任务。
 *
 * <p>这是「实时层」，定时扫描是「兜底层」：前者让刚上线的人立刻有机会接到
 * 之前因全员未签到而 QUEUED 的任务，后者保证事件丢失时最终仍会流转。
 * 监听器绝不抛异常影响签到主流程——AFTER_COMMIT 已提交，异常只记日志。
 */
public class AgentOnlineRedispatchListener {

    private static final Logger log = LoggerFactory.getLogger(AgentOnlineRedispatchListener.class);

    private final WorkflowDispatchService dispatchService;

    public AgentOnlineRedispatchListener(WorkflowDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentOnline(AgentOnlineEvent event) {
        try {
            int handled = dispatchService.redispatchForUser(event.tenantId(), event.userId());
            if (handled > 0) {
                log.info("坐席上线触发待派重派 tenant={} user={} source={} tasks={}",
                        event.tenantId(), event.userId(), event.source(), handled);
            }
        } catch (Exception ex) {
            log.warn("坐席上线重派失败 tenant={} user={}（不影响签到）：{}",
                    event.tenantId(), event.userId(), ex.getMessage());
        }
    }
}
