package com.example.baseplate.workflow.application.dispatch;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * application 层和 Flowable 之间的窄接口：只暴露分单需要的动作。
 * 这样核心服务不 import DelegateTask / TaskService，架构测试也能守住边界。
 */
public interface DispatchTaskGateway {

    void assign(String taskId, String userId);

    void unassign(String taskId);

    boolean claim(String taskId, String userId);

    Optional<TaskInfo> taskInfo(String taskId);

    Optional<String> historicAssignee(String taskId);

    boolean finished(String taskId);

    record TaskInfo(
            String taskId,
            String instanceId,
            String processKey,
            int definitionVersion,
            String nodeId,
            String assignee,
            Map<String, Object> variables) {

        public TaskInfo {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    /** 任务可见性：池内组织展开后的用户集合才允许抢单。 */
    record PoolVisibility(List<Long> orgIds) {
    }
}
