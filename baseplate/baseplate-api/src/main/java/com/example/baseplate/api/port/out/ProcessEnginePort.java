package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.Decision;
import com.example.baseplate.api.model.FormData;
import com.example.baseplate.api.model.MigrationReport;
import com.example.baseplate.api.model.PageQuery;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.api.model.ProcessDefinitionView;
import com.example.baseplate.api.model.ProcessInstanceView;
import com.example.baseplate.api.model.TaskView;
import com.example.baseplate.api.model.WorkflowDefinitionDetail;
import com.example.baseplate.api.model.WorkflowDoneTaskView;
import com.example.baseplate.api.model.WorkflowInstanceDetail;
import com.example.baseplate.api.model.WorkflowInstancePage;
import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.api.model.WorkflowOperationView;
import com.example.baseplate.api.model.WorkflowOverview;
import com.example.baseplate.api.model.WorkflowTaskDetail;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程引擎端口：业务层只认识这些方法，不认识 Flowable 的 RuntimeService/TaskService。
 * 换引擎时只改适配器，内核零改动。
 */
public interface ProcessEnginePort {
    /** 兼容旧调用：不带节点配置。 */
    default String deploy(String bpmnXml, String processKey) {
        return deploy(bpmnXml, processKey, List.of());
    }

    /** 结构化节点配置由服务端编译成 BPMN，用户不写表达式。 */
    String deploy(String bpmnXml, String processKey, List<WorkflowNodeConfig> nodeConfigs);

    String start(String processKey, String businessKey, Map<String, Object> variables);

    default List<TaskView> todo(CurrentUser user, PageQuery page) {
        return todo(user, page, null, null);
    }

    /** 我的待办：processKey/keyword 可选过滤，keyword 匹配任务名与业务单号。 */
    List<TaskView> todo(CurrentUser user, PageQuery page, String processKey, String keyword);

    /** 我的已办：我办理过的历史任务，按办结时间倒序。 */
    PageResult<WorkflowDoneTaskView> done(CurrentUser user, PageQuery page, String processKey, String keyword);

    /** 任务办理详情：运行中或历史任务都可查；canHandle 由服务端按归属判定。 */
    WorkflowTaskDetail taskDetail(String taskId);

    void complete(String taskId, Decision decision, FormData data);

    /** 非 BPMN 标准符号，属于引擎扩展能力。 */
    void rollback(String taskId, String targetNodeId, String reason);

    void transfer(String taskId, String toUserId);

    void addSign(String taskId, List<String> userIds);

    /** 已部署流程定义；返回全部版本，不能只给 latestVersion。 */
    List<ProcessDefinitionView> definitions();

    /**
     * 概览数字：定义数、运行中/已结束实例数、已挂起定义数。
     * 首页只要这几个数，不该为了数量去拉 {@link #definitions()} 的全部明细
     * （明细要按定义逐个统计实例，几十个定义就是上百次查询）。
     */
    default WorkflowOverview overview() {
        List<ProcessDefinitionView> all = definitions();
        int running = all.stream().mapToInt(ProcessDefinitionView::runningInstances).sum();
        int finished = all.stream().mapToInt(ProcessDefinitionView::finishedInstances).sum();
        int suspended = (int) all.stream().filter(ProcessDefinitionView::suspended).count();
        return new WorkflowOverview(all.size(), running, finished, suspended);
    }

    /** 按办理人查任务：管理员排查「为什么某人看不到待办」时用。 */
    List<TaskView> tasks(String assignee, PageQuery page);

    /** 待办真总数：分页控件不能再用 -1 糊弄。 */
    default long taskCount(String assignee) {
        return taskCount(assignee, null, null);
    }

    long taskCount(String assignee, String processKey, String keyword);

    /** 实例列表：包含已结束实例，用于监控与演示。 */
    List<ProcessInstanceView> instances(int limit);

    /** 某实例下的全部任务。 */
    List<TaskView> tasksOfInstance(String processInstanceId);

    /** 定义详情：版本历史、实例统计、挂起状态。 */
    WorkflowDefinitionDetail definitionDetail(String processDefinitionId);

    /** 可渲染的 BPMN XML：缺 DI 的存量流程会在返回时补齐坐标，不改库里的部署内容。 */
    String definitionXml(String processDefinitionId);

    /** 实例详情：已结束实例也必须能查到完整轨迹与图状态。 */
    WorkflowInstanceDetail instanceDetail(String processInstanceId);

    /** 实例分页：state=RUNNING/FINISHED/ALL，keyword 匹配业务单号。 */
    default WorkflowInstancePage instancePage(int page, int size, String state,
                                              String processKey, String keyword) {
        return instancePage(page, size, state, processKey, keyword, null);
    }

    /**
     * startUserIds 是数据权限下传的显式条件：null=不限（管理员/ALL 范围），
     * 空集合=看不到任何实例，非空=只看这些发起人。
     */
    WorkflowInstancePage instancePage(int page, int size, String state, String processKey,
                                      String keyword, Set<String> startUserIds);

    /** 实例发起人 userId；不存在或跨租户时抛 IllegalArgumentException。 */
    String instanceStartUserId(String processInstanceId);

    /** 是否参与过该实例（历史办理人或当前候选人）：数据范围外的人凭参与关系可见。 */
    boolean isParticipant(String processInstanceId, String userId);

    List<WorkflowOperationView> operations(String processInstanceId);

    /** 作废：挂起定义但**不挂起已有实例**，老实例继续走完。 */
    void suspendDefinition(String processDefinitionId);

    void activateDefinition(String processDefinitionId);

    /** 只做预检，不写任何数据；issues 非空说明整体迁移会被拒绝。 */
    MigrationReport validateMigration(String fromDefinitionId, String toDefinitionId);

    /** 保留当前节点，把该定义下全部运行中实例改挂到新版本。 */
    MigrationReport migrateInstances(String fromDefinitionId, String toDefinitionId);

    void terminateInstance(String processInstanceId, String reason);
}
