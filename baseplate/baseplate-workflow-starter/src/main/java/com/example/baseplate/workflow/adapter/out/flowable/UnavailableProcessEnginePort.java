package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.Decision;
import com.example.baseplate.api.model.FormData;
import com.example.baseplate.api.model.PageQuery;
import com.example.baseplate.api.model.ProcessDefinitionView;
import com.example.baseplate.api.model.ProcessInstanceView;
import com.example.baseplate.api.model.TaskView;
import com.example.baseplate.api.port.out.ProcessEnginePort;

import java.util.List;
import java.util.Map;

/**
 * 引擎未就绪时的占位实现：应用照常启动，调用流程接口时明确报错。
 * 比让整个应用起不来更可控，也不会伪装成「流程可用」。
 */
public final class UnavailableProcessEnginePort implements ProcessEnginePort {

    private IllegalStateException unavailable() {
        return new IllegalStateException("流程引擎未启用：请检查 Flowable 配置与数据源");
    }

    @Override
    public String deploy(String bpmnXml, String processKey,
                         java.util.List<com.example.baseplate.api.model.WorkflowNodeConfig> nodeConfigs) {
        throw unavailable();
    }

    @Override
    public String start(String processKey, String businessKey, Map<String, Object> variables) {
        throw unavailable();
    }

    @Override
    public List<TaskView> todo(CurrentUser user, PageQuery page, String processKey, String keyword) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.PageResult<com.example.baseplate.api.model.WorkflowDoneTaskView> done(
            CurrentUser user, PageQuery page, String processKey, String keyword) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.WorkflowTaskDetail taskDetail(String taskId) {
        throw unavailable();
    }

    @Override
    public void complete(String taskId, Decision decision, FormData data) {
        throw unavailable();
    }

    @Override
    public void rollback(String taskId, String targetNodeId, String reason) {
        throw unavailable();
    }

    @Override
    public void transfer(String taskId, String toUserId) {
        throw unavailable();
    }

    @Override
    public void addSign(String taskId, List<String> userIds) {
        throw unavailable();
    }

    @Override
    public long taskCount(String assignee, String processKey, String keyword) {
        throw unavailable();
    }

    @Override
    public List<ProcessDefinitionView> definitions() {
        throw unavailable();
    }

    @Override
    public List<TaskView> tasks(String assignee, PageQuery page) {
        throw unavailable();
    }

    @Override
    public List<ProcessInstanceView> instances(int limit) {
        throw unavailable();
    }

    @Override
    public List<TaskView> tasksOfInstance(String processInstanceId) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.WorkflowDefinitionDetail definitionDetail(String id) {
        throw unavailable();
    }

    @Override
    public String definitionXml(String id) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.WorkflowInstanceDetail instanceDetail(String id) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.WorkflowInstancePage instancePage(int page, int size,
            String state, String processKey, String keyword, java.util.Set<String> startUserIds) {
        throw unavailable();
    }

    @Override
    public String instanceStartUserId(String processInstanceId) {
        throw unavailable();
    }

    @Override
    public boolean isParticipant(String processInstanceId, String userId) {
        throw unavailable();
    }

    @Override
    public List<com.example.baseplate.api.model.WorkflowOperationView> operations(String id) {
        throw unavailable();
    }

    @Override
    public void suspendDefinition(String id) {
        throw unavailable();
    }

    @Override
    public void activateDefinition(String id) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.MigrationReport validateMigration(String from, String to) {
        throw unavailable();
    }

    @Override
    public com.example.baseplate.api.model.MigrationReport migrateInstances(String from, String to) {
        throw unavailable();
    }

    @Override
    public void terminateInstance(String id, String reason) {
        throw unavailable();
    }
}
