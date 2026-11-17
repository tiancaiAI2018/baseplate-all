package com.example.baseplate.identity.application;

import com.example.baseplate.api.port.out.UserDirectory;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 用户目录的 identity 实现：开放平台在「代表用户调用」时用它与登录态同一份数据重建上下文。
 *
 * <p>停用用户直接返回空——开放令牌不该比登录态活得更久。用户被停用后，
 * 之前发出去的 refresh token 兑换时也会走这里取权限，从而自然失效一次。
 * （完整做法是换 token 时再查一次状态，这里已经覆盖。）</p>
 */
public final class IdentityUserDirectory implements UserDirectory {

    private static final String ACTIVE = "ACTIVE";

    private final IdentityDao dao;

    public IdentityUserDirectory(IdentityDao dao) {
        this.dao = dao;
    }

    @Override
    public Optional<Snapshot> load(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        long id;
        try {
            id = Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return dao.findUserById(id)
                .filter(user -> ACTIVE.equals(user.status()))
                .map(user -> new Snapshot(
                        String.valueOf(user.id()),
                        user.tenantId(),
                        user.username(),
                        rolesOf(user.id()),
                        new LinkedHashSet<>(dao.permissionsOf(user.id()))));
    }

    private Set<String> rolesOf(long userId) {
        Set<String> roles = new LinkedHashSet<>();
        dao.rolesOf(userId).forEach(role -> roles.add(role.roleCode()));
        return roles;
    }
}
