package com.example.baseplate.workflow.application;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import org.flowable.engine.RepositoryService;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一被允许出现在 BPMN 里的受控监听器（由 NodeConfigCompiler 注入，用户无法手写）。
 * 单签/或签在任务创建后按节点配置重解析办理人；会签的多实例集合由流程变量预置。
 */
@Component("wfAssigneeResolver")
public final class AssigneeResolverListener implements TaskListener {

    private final WorkflowConfigRepository repository;
    private final AssigneeResolver resolver;
    private final ObjectProvider<RepositoryService> repositoryService;

    public AssigneeResolverListener(WorkflowConfigRepository repository, AssigneeResolver resolver,
                                    ObjectProvider<RepositoryService> repositoryService) {
        this.repository = repository;
        this.resolver = resolver;
        this.repositoryService = repositoryService;
    }

    @Override
    public void notify(DelegateTask task) {
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
        var config = repository.nodeConfig(tenant, definition.getKey(),
                definition.getVersion(), task.getTaskDefinitionKey());
        if (config.isEmpty()) {
            return;
        }
        WorkflowNodeConfig node = config.get();
        String sign = node.signType().toUpperCase();
        if ("COUNTERSIGN".equals(sign) || "SEQUENTIAL".equals(sign)) {
            return; // 多实例由 ${wfAssignee} 与集合变量驱动
        }
        Map<String, Object> vars = new HashMap<>();
        for (String name : task.getVariables().keySet()) {
            vars.put(name, task.getVariable(name));
        }
        List<String> users = resolver.usersFor(tenant, node, vars);
        if (users.isEmpty()) {
            return;
        }
        if ("OR_SIGN".equals(sign)) {
            for (String userId : users) {
                task.addCandidateUser(userId);
            }
        } else {
            task.setAssignee(users.get(0));
        }
    }
}
