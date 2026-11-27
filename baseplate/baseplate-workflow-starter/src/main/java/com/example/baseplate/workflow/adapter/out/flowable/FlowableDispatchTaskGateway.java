package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.workflow.application.dispatch.DispatchTaskGateway;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 分单域和 Flowable 的唯一适配层。application 只看到 DispatchTaskGateway，
 * TaskService / HistoryService 不外泄，ArchUnit 的边界才守得住。
 */
public final class FlowableDispatchTaskGateway implements DispatchTaskGateway {

    private final ObjectProvider<TaskService> taskService;
    private final ObjectProvider<HistoryService> historyService;
    private final ObjectProvider<RepositoryService> repositoryService;
    private final ObjectProvider<RuntimeService> runtimeService;

    public FlowableDispatchTaskGateway(ObjectProvider<TaskService> taskService,
                                       ObjectProvider<HistoryService> historyService,
                                       ObjectProvider<RepositoryService> repositoryService,
                                       ObjectProvider<RuntimeService> runtimeService) {
        this.taskService = taskService;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
    }

    @Override
    public void assign(String taskId, String userId) {
        tasks().setAssignee(taskId, userId);
    }

    @Override
    public void unassign(String taskId) {
        tasks().setAssignee(taskId, null);
    }

    @Override
    public boolean claim(String taskId, String userId) {
        try {
            tasks().claim(taskId, userId);
            return true;
        } catch (RuntimeException ex) {
            // Flowable 的 claim 自带乐观校验；抛异常说明已被别人认领
            return false;
        }
    }

    @Override
    public Optional<TaskInfo> taskInfo(String taskId) {
        Task task = tasks().createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return Optional.empty();
        }
        ProcessDefinition definition = repository().createProcessDefinitionQuery()
                .processDefinitionId(task.getProcessDefinitionId()).singleResult();
        Map<String, Object> variables = new HashMap<>();
        RuntimeService runtime = runtimeService.getIfAvailable();
        if (runtime != null && task.getProcessInstanceId() != null) {
            variables.putAll(runtime.getVariables(task.getProcessInstanceId()));
        }
        return Optional.of(new TaskInfo(task.getId(), task.getProcessInstanceId(),
                definition == null ? null : definition.getKey(),
                definition == null ? 0 : definition.getVersion(),
                task.getTaskDefinitionKey(), task.getAssignee(), variables));
    }

    @Override
    public Optional<String> historicAssignee(String taskId) {
        HistoryService history = historyService.getIfAvailable();
        if (history == null) {
            return Optional.empty();
        }
        HistoricTaskInstance instance = history.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        return instance == null ? Optional.empty() : Optional.ofNullable(instance.getAssignee());
    }

    @Override
    public boolean finished(String taskId) {
        HistoryService history = historyService.getIfAvailable();
        if (history == null) {
            return false;
        }
        HistoricTaskInstance instance = history.createHistoricTaskInstanceQuery()
                .taskId(taskId).singleResult();
        return instance != null && instance.getEndTime() != null;
    }

    private TaskService tasks() {
        TaskService service = taskService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Flowable TaskService 未装配，无法分单");
        }
        return service;
    }

    private RepositoryService repository() {
        RepositoryService service = repositoryService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Flowable RepositoryService 未装配，无法分单");
        }
        return service;
    }
}
