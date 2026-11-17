package com.example.baseplate.identity.application;

import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DataScope;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.DataScopeProvider;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;
import com.example.baseplate.identity.config.IdentityProperties;
import com.example.baseplate.identity.domain.PasswordHasher;
import com.example.baseplate.identity.domain.PasswordPolicy;
import com.example.baseplate.security.jwt.TokenBlacklist;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

/** 用户管理：管理员/高级角色对下级人员增删改查、重置密码。 */
public final class UserAdminService {

    private final IdentityDao dao;
    private final CurrentUserProvider currentUser;
    private final DataScopeProvider dataScopeProvider;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final IdentityProperties properties;
    private final TokenBlacklist blacklist;
    private final SecureRandom random = new SecureRandom();

    public UserAdminService(IdentityDao dao, CurrentUserProvider currentUser,
                            DataScopeProvider dataScopeProvider, PasswordHasher hasher,
                            PasswordPolicy policy, IdentityProperties properties,
                            TokenBlacklist blacklist) {
        this.dao = dao;
        this.currentUser = currentUser;
        this.dataScopeProvider = dataScopeProvider;
        this.hasher = hasher;
        this.policy = policy;
        this.properties = properties;
        this.blacklist = blacklist;
    }

    public record UserView(long id, String username, String displayName, Long orgId,
                           String status, boolean mustChangePassword,
                           int failedAttempts, String lockedUntil, List<String> roles) {
    }

    public record CreateCommand(String username, String password, String displayName,
                                Long orgId, List<Long> roleIds) {
    }

    public record UpdateCommand(String displayName, Long orgId, String status, List<Long> roleIds) {
    }

    public PageResult<UserView> list(String keyword, int page, int size) {
        CurrentUser user = currentUser.current();
        DataScope scope = dataScopeProvider.scopeOf(user, "User");
        Long onlyUserId = null;
        List<Long> orgIds = null;
        switch (scope.kind()) {
            case ALL -> {
            }
            case SELF -> onlyUserId = Long.parseLong(user.userId());
            case ORG, ORG_AND_CHILDREN, CUSTOM -> orgIds = List.copyOf(scope.orgIds());
        }
        int offset = (page - 1) * size;
        List<UserView> items = dao.searchUsers(user.tenantId(), keyword, orgIds, onlyUserId, size, offset)
                .stream().map(this::toView).toList();
        long total = dao.countUsers(user.tenantId(), keyword, orgIds, onlyUserId);
        return new PageResult<>(items, total);
    }

    public UserView create(CreateCommand command) {
        CurrentUser user = currentUser.current();
        String username = command.username() == null ? "" : command.username().trim();
        if (!username.matches("^[a-zA-Z0-9_.-]{3,32}$")) {
            throw new IllegalArgumentException("用户名必须为 3-32 位字母、数字、下划线、点或短横线");
        }
        if (dao.usernameExists(user.tenantId(), username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        String initial = command.password();
        if (initial == null || initial.isBlank()) {
            initial = "Init@" + randomAlnum(8) + "7";
        }
        policy.validate(initial, username);
        long id = dao.insertUser(user.tenantId(), username, hasher.hash(initial),
                command.displayName() == null ? username : command.displayName(),
                command.orgId(), true);
        if (command.roleIds() != null && !command.roleIds().isEmpty()) {
            dao.replaceUserRoles(id, command.roleIds());
        }
        dao.insertPasswordHistory(id, hasher.hash(initial));
        return toView(dao.findUserById(id).orElseThrow());
    }

    public void update(long id, UpdateCommand command) {
        dao.findUserById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String status = command.status() == null ? "ACTIVE" : command.status();
        dao.updateUserBasic(id, command.displayName(), command.orgId(), status);
        if (command.roleIds() != null) {
            dao.replaceUserRoles(id, command.roleIds());
        }
        if (!"ACTIVE".equals(status)) {
            blacklist.revokeAllForUser(String.valueOf(id));
        }
    }

    public void disable(long id) {
        CurrentUser user = currentUser.current();
        if (String.valueOf(id).equals(user.userId())) {
            throw new ForbiddenException("不能停用自己");
        }
        dao.updateUserBasic(id, null, null, "DISABLED");
        blacklist.revokeAllForUser(String.valueOf(id));
    }

    /** 返回一次性明文初始密码，服务端不保存明文。 */
    public String resetPassword(long id, String newPassword) {
        IdentityDao.UserRow target = dao.findUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String password = (newPassword == null || newPassword.isBlank())
                ? "Init@" + randomAlnum(8) + "7" : newPassword;
        policy.validate(password, target.username());
        dao.updatePassword(id, hasher.hash(password), true);
        dao.insertPasswordHistory(id, hasher.hash(password));
        // 重置密码时顺带解锁：否则用户拿着新的一次性密码仍被「账号锁定中」挡在门外
        dao.unlock(id);
        blacklist.revokeAllForUser(String.valueOf(id));
        return password;
    }

    /** 管理员手动解锁：只清失败计数与锁定时点，不动密码、不改首登标记。 */
    public void unlock(long id) {
        dao.findUserById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        dao.unlock(id);
    }

    public void assignRoles(long id, List<Long> roleIds) {
        dao.replaceUserRoles(id, roleIds);
    }

    private UserView toView(IdentityDao.UserRow row) {
        List<String> roles = dao.rolesOf(row.id()).stream()
                .map(IdentityDao.RoleRow::roleCode).toList();
        return new UserView(row.id(), row.username(), row.displayName(), row.orgId(),
                row.status(), row.mustChangePassword(), row.failedAttempts(),
                row.lockedUntil() == null ? null : row.lockedUntil().toString(), roles);
    }

    private String randomAlnum(int length) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
