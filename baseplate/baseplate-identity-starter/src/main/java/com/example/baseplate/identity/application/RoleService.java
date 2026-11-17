package com.example.baseplate.identity.application;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;

import java.util.List;

/** 角色：角色—权限—用户三段绑定，内置角色不可删除。 */
public final class RoleService {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final IdentityDao dao;
    private final CurrentUserProvider currentUser;

    public RoleService(IdentityDao dao, CurrentUserProvider currentUser) {
        this.dao = dao;
        this.currentUser = currentUser;
    }

    public record RoleView(long id, String roleCode, String roleName, String dataScope,
                           boolean builtIn, List<String> permissions) {
    }

    public List<RoleView> list() {
        String tenant = currentUser.current().tenantId();
        return dao.listRoles(tenant).stream().map(role -> new RoleView(
                        role.id(), role.roleCode(), role.roleName(), role.dataScope(),
                        role.builtIn(), dao.rolePermissions(role.id())))
                .toList();
    }

    public long create(String code, String name, String dataScope, List<String> permissions) {
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{2,31}$")) {
            throw new IllegalArgumentException("角色编码必须为大写字母、数字、下划线，3-32 位");
        }
        String tenant = currentUser.current().tenantId();
        long id = dao.insertRole(tenant, code, name == null ? code : name,
                dataScope == null ? "SELF" : dataScope);
        if (permissions != null) {
            dao.replaceRolePermissions(id, permissions);
        }
        return id;
    }

    public void update(long id, String name, String dataScope) {
        IdentityDao.RoleRow role = dao.findRole(currentUser.current().tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (role.builtIn() && !SUPER_ADMIN.equals(role.roleCode())) {
            throw new IllegalArgumentException("内置角色不可修改");
        }
        dao.updateRole(id, name, dataScope);
    }

    public void delete(long id) {
        IdentityDao.RoleRow role = dao.findRole(currentUser.current().tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (role.builtIn()) {
            throw new IllegalArgumentException("内置角色不可删除");
        }
        dao.deleteRole(id);
    }

    public void setPermissions(long id, List<String> permissions) {
        dao.findRole(currentUser.current().tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        dao.replaceRolePermissions(id, permissions);
    }
}
