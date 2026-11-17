package com.example.baseplate.workflow.application;

import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DataScope;
import com.example.baseplate.api.model.WorkflowProcessSetting;
import com.example.baseplate.api.port.out.DataScopeProvider;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流数据权限：把角色上的 data_scope 翻译成显式查询条件，不用 SQL 拦截器。
 *
 * 两条线：
 * - 发起侧：wf_process_setting.start_scope 决定「谁能发起这个流程」；
 * - 可见侧：DataScopeProvider("WorkflowInstance") 决定「实例列表能看到谁发起的」，
 *   范围外的人如果参与过该实例（办理人/候选人）仍然可见——审批人必须能看自己办的单。
 */
public final class WorkflowAccessService {

    public static final String AGGREGATE = "WorkflowInstance";

    private final WorkflowConfigRepository repository;
    private final DataScopeProvider dataScope;
    private final ProcessEnginePort engine;

    public WorkflowAccessService(WorkflowConfigRepository repository,
                                 DataScopeProvider dataScope, ProcessEnginePort engine) {
        this.repository = repository;
        this.dataScope = dataScope;
        this.engine = engine;
    }

    public boolean isAdmin(CurrentUser user) {
        return user != null && (user.roles().contains("SUPER_ADMIN")
                || user.permissions().contains("wf:manage"));
    }

    /** 发起权限：未配置默认全员（ALL）；配置停用或范围不匹配则拒绝。管理员不受限。 */
    public boolean canStart(CurrentUser user, WorkflowProcessSetting setting) {
        if (isAdmin(user)) {
            return true;
        }
        if (setting == null) {
            return true;
        }
        if (!setting.enabled()) {
            return false;
        }
        List<String> values = split(setting.startScopeValue());
        return switch (setting.startScopeType() == null ? "ALL" : setting.startScopeType()) {
            case "ROLE" -> values.stream().anyMatch(user.roles()::contains);
            case "USER" -> values.contains(user.userId());
            case "ORG" -> {
                Long orgId = repository.orgOfUser(user.tenantId(), user.userId());
                yield orgId != null && values.contains(String.valueOf(orgId));
            }
            default -> true;
        };
    }

    public void requireStart(CurrentUser user, WorkflowProcessSetting setting, String processKey) {
        if (!canStart(user, setting)) {
            throw new ForbiddenException("没有流程「" + processKey + "」的发起权限，请联系管理员调整发起范围");
        }
    }

    /**
     * 实例可见范围对应的发起人集合：null=不限（管理员或 ALL），
     * 空集合=只能看自己参与过的（列表查询返回空，详情走参与关系兜底）。
     */
    public Set<String> visibleStartUserIds(CurrentUser user) {
        if (isAdmin(user)) {
            return null;
        }
        DataScope scope = dataScope == null ? null : dataScope.scopeOf(user, AGGREGATE);
        if (scope == null || scope.kind() == DataScope.ScopeKind.ALL) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(user.userId());
        if (scope.userIds() != null) {
            ids.addAll(scope.userIds());
        }
        if (scope.kind() == DataScope.ScopeKind.ORG || scope.kind() == DataScope.ScopeKind.ORG_AND_CHILDREN) {
            ids.addAll(repository.userIdsInOrgs(user.tenantId(), scope.orgIds()));
        }
        return ids;
    }

    /** 实例详情/图/表单数据的可见性：管理范围 → 发起人范围 → 参与关系，三层任一命中即可。 */
    public void requireInstanceView(CurrentUser user, String instanceId, String startUserId) {
        if (isAdmin(user)) {
            return;
        }
        Set<String> allowed = visibleStartUserIds(user);
        if (allowed == null || (startUserId != null && allowed.contains(startUserId))) {
            return;
        }
        if (engine.isParticipant(instanceId, user.userId())) {
            return;
        }
        throw new ForbiddenException("该流程实例不在你的数据范围内");
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}