package com.example.baseplate.identity.application;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;
import com.example.baseplate.identity.config.IdentityProperties;
import com.example.baseplate.identity.domain.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 初始管理员：只在一个 SUPER_ADMIN 都没有时创建，绝不在每次启动重置密码。
 * 初始密码来自 BASEPLATE_ADMIN_PASSWORD；未配置时用固定值，但强制首登改密。
 */
@Order(20)
public final class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final IdentityDao dao;
    private final PasswordHasher hasher;
    private final IdentityProperties properties;
    private final ModuleRegistry moduleRegistry;

    public AdminSeeder(IdentityDao dao, PasswordHasher hasher, IdentityProperties properties,
                       ModuleRegistry moduleRegistry) {
        this.dao = dao;
        this.hasher = hasher;
        this.properties = properties;
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isAdminSeedEnabled()) {
            return;
        }
        String tenant = properties.getDefaultTenant();
        try {
            long roleId = ensureSuperAdminRole(tenant);
            syncSuperAdminPermissions(roleId);
            if (dao.countUsersWithRole(tenant, RoleService.SUPER_ADMIN) == 0) {
                createAdmin(tenant, roleId);
            }
            ensureDefaultOrg(tenant);
        } catch (RuntimeException e) {
            // 初始化失败不能静默：打印后继续启动，登录会暴露问题
            log.error("初始管理员初始化失败", e);
        }
    }

    private long ensureSuperAdminRole(String tenant) {
        Optional<IdentityDao.RoleRow> existing = dao.listRoles(tenant).stream()
                .filter(role -> RoleService.SUPER_ADMIN.equals(role.roleCode()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().id();
        }
        long id = dao.insertRole(tenant, RoleService.SUPER_ADMIN, "超级管理员", "ALL");
        dao.markRoleBuiltIn(id);
        return id;
    }

    private void syncSuperAdminPermissions(long roleId) {
        List<String> permissions = new ArrayList<>();
        for (ModuleDescriptor module : moduleRegistry.loaded()) {
            permissions.addAll(module.permissions());
        }
        dao.replaceRolePermissions(roleId, permissions);
    }

    private void createAdmin(String tenant, long roleId) {
        String username = properties.getAdminUsername();
        String password = properties.getAdminPassword();
        Long orgId = dao.listOrgs(tenant).stream().findFirst().map(IdentityDao.OrgRow::id).orElse(null);
        long userId;
        if (dao.usernameExists(tenant, username)) {
            userId = dao.findUser(tenant, username).orElseThrow().id();
        } else {
            userId = dao.insertUser(tenant, username, hasher.hash(password), "系统管理员", orgId, true);
            dao.insertPasswordHistory(userId, hasher.hash(password));
            log.warn("已创建初始管理员 {}，首次登录必须修改密码（初始密码来自 BASEPLATE_ADMIN_PASSWORD）", username);
        }
        dao.replaceUserRoles(userId, List.of(roleId));
    }

    private void ensureDefaultOrg(String tenant) {
        if (dao.listOrgs(tenant).isEmpty()) {
            long orgId = dao.insertOrg(tenant, null, "总部", "/pending");
            dao.updateOrgPath(orgId, "/" + orgId);
        }
    }
}
