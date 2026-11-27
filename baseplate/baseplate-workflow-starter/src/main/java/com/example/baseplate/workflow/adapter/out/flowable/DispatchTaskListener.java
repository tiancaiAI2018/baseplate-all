package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import com.example.baseplate.workflow.application.dispatch.DispatchRequest;
import com.example.baseplate.workflow.application.dispatch.WorkflowDispatchService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 分单节点唯一的 create 监听器（由 NodeConfigCompiler 按 assigneeType=DISPATCH 注入）。
 * 只处理 DISPATCH 配置，审批流节点仍走 AssigneeResolverListener，两者不交叉。
 *
 * 异常必须吞掉：分单失败不能让流程发起失败，任务留空进待派池由人工兜底。
 */
@Component("wfDispatchListener")
public final class DispatchTaskListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(DispatchTaskListener.class);

    private final WorkflowDispatchService dispatchService;
    private final WorkflowConfigRepository configRepository;
    private final ObjectProvider<RepositoryService> repositoryService;

    public DispatchTaskListener(WorkflowDispatchService dispatchService,
                                WorkflowConfigRepository configRepository,
                                ObjectProvider<RepositoryService> repositoryService) {
        this.dispatchService = dispatchService;
        this.configRepository = configRepository;
        this.repositoryService = repositoryService;
    }

    @Override
    public void notify(DelegateTask task) {
        try {
            if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
                return; // 异步执行器重试时幂等
            }
            String tenant = task.getTenantId();
            if (tenant == null || tenant.isBlank()) {
                tenant = "default";
            }
            RepositoryService service = repositoryService.getIfAvailable();
            if (service == null) {
                return;
            }
            ProcessDefinition definition = service.createProcessDefinitionQuery()
                    .processDefinitionId(task.getProcessDefinitionId()).singleResult();
            if (definition == null) {
                return;
            }
            var config = configRepository.nodeConfig(tenant, definition.getKey(),
                    definition.getVersion(), task.getTaskDefinitionKey());
            if (config.isEmpty() || !"DISPATCH".equalsIgnoreCase(config.get().assigneeType())) {
                return;
            }
            Map<String, Object> variables = new HashMap<>();
            for (String name : task.getVariables().keySet()) {
                variables.put(name, task.getVariable(name));
            }
            dispatchService.dispatch(new DispatchRequest(tenant, definition.getKey(),
                    definition.getVersion(), task.getTaskDefinitionKey(), task.getProcessInstanceId(),
                    task.getId(), variables, String.valueOf(variables.getOrDefault("initiator", ""))));
        } catch (RuntimeException ex) {
            log.error("分单监听失败 task={}，任务进入待派池", task.getId(), ex);
        }
    }
}
