package com.example.baseplate.identity.application;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DataScope;
import com.example.baseplate.api.port.out.DataScopeProvider;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据权限：取用户所有角色里最大的范围，再算组织子树。
 * 不用 MyBatis-Plus 拦截器改写 SQL —— 范围作为显式查询条件下传，换 ORM 不受影响。
 */
public final class DefaultDataScopeProvider implements DataScopeProvider {

    private final IdentityDao dao;

    public DefaultDataScopeProvider(IdentityDao dao) {
        this.dao = dao;
    }

    @Override
    public DataScope scopeOf(CurrentUser user, String aggregate) {
        if (user == null) {
            return new DataScope(DataScope.ScopeKind.SELF, Set.of(), Set.of(), null);
        }
        List<IdentityDao.RoleRow> roles = dao.rolesOf(Long.parseLong(user.userId()));
        DataScope.ScopeKind kind = resolveKind(roles);
        Set<Long> orgIds = new LinkedHashSet<>();
        if (kind == DataScope.ScopeKind.ORG || kind == DataScope.ScopeKind.ORG_AND_CHILDREN) {
            dao.findUserById(Long.parseLong(user.userId()))
                    .map(IdentityDao.UserRow::orgId)
                    .ifPresent(orgId -> {
                        if (kind == DataScope.ScopeKind.ORG_AND_CHILDREN) {
                            orgIds.addAll(dao.orgSubtreeIds(orgId));
                        } else {
                            orgIds.add(orgId);
                        }
                    });
        }
        return new DataScope(kind, orgIds, Set.of(user.userId()), user.tenantId());
    }

    private DataScope.ScopeKind resolveKind(List<IdentityDao.RoleRow> roles) {
        boolean orgAndChildren = false;
        boolean org = false;
        boolean custom = false;
        for (IdentityDao.RoleRow role : roles) {
            switch (role.dataScope()) {
                case "ALL" -> {
                    return DataScope.ScopeKind.ALL;
                }
                case "ORG_AND_CHILDREN" -> orgAndChildren = true;
                case "ORG" -> org = true;
                case "CUSTOM" -> custom = true;
                default -> {
                }
            }
        }
        if (orgAndChildren) {
            return DataScope.ScopeKind.ORG_AND_CHILDREN;
        }
        if (org) {
            return DataScope.ScopeKind.ORG;
        }
        if (custom) {
            return DataScope.ScopeKind.CUSTOM;
        }
        return DataScope.ScopeKind.SELF;
    }
}
