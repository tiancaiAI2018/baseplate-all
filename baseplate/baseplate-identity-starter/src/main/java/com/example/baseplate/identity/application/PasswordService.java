package com.example.baseplate.identity.application;

import com.example.baseplate.api.exception.AuthenticationException;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;
import com.example.baseplate.identity.config.IdentityProperties;
import com.example.baseplate.identity.domain.PasswordHasher;
import com.example.baseplate.identity.domain.PasswordPolicy;
import com.example.baseplate.security.jwt.TokenBlacklist;

/**
 * 改密用例：策略、历史、令牌吊销三件事必须在同一入口完成。
 * 任何「管理员重置」也走这里，避免出现绕过策略的第二条路径。
 */
public final class PasswordService {

    private final IdentityDao dao;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final IdentityProperties properties;
    private final TokenBlacklist blacklist;

    public PasswordService(IdentityDao dao, PasswordHasher hasher, PasswordPolicy policy,
                           IdentityProperties properties, TokenBlacklist blacklist) {
        this.dao = dao;
        this.hasher = hasher;
        this.policy = policy;
        this.properties = properties;
        this.blacklist = blacklist;
    }

    public void changeOwnPassword(long userId, String oldPassword, String newPassword) {
        IdentityDao.UserRow user = dao.findUserById(userId)
                .orElseThrow(() -> new AuthenticationException("用户不存在"));
        if (!hasher.matches(oldPassword, user.passwordHash())) {
            throw new AuthenticationException("原密码不正确");
        }
        applyNewPassword(user, newPassword);
    }

    public void resetByAdmin(long userId, String newPassword) {
        IdentityDao.UserRow user = dao.findUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        applyNewPassword(user, newPassword);
    }

    private void applyNewPassword(IdentityDao.UserRow user, String newPassword) {
        policy.validate(newPassword, user.username());
        if (dao.recentPasswordHashes(user.id(), properties.getPasswordHistorySize())
                .stream().anyMatch(hash -> hasher.matches(newPassword, hash))) {
            throw new IllegalArgumentException("不能使用最近使用过的密码");
        }
        dao.updatePassword(user.id(), hasher.hash(newPassword), false);
        dao.insertPasswordHistory(user.id(), hasher.hash(newPassword));
        // 改密后所有旧令牌立即失效，不等自然过期
        blacklist.revokeAllForUser(String.valueOf(user.id()));
    }
}
