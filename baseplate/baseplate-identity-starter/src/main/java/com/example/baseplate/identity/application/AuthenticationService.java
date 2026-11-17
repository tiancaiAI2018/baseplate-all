package com.example.baseplate.identity.application;

import com.example.baseplate.api.exception.AuthenticationException;
import com.example.baseplate.api.model.AuthResult;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;
import com.example.baseplate.identity.config.IdentityProperties;
import com.example.baseplate.identity.domain.PasswordHasher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 登录用例：锁定检查 → 口令校验 → 状态检查 → 密码过期判定 → 签发令牌。
 * 失败计数与登录日志都写库，不依赖日志文件取证。
 */
public final class AuthenticationService {

    private static final String ACTIVE = "ACTIVE";

    private final IdentityDao dao;
    private final PasswordHasher hasher;
    private final JwtIssuer jwt;
    private final IdentityProperties properties;
    private final com.example.baseplate.security.jwt.TokenBlacklist blacklist;

    public AuthenticationService(IdentityDao dao, PasswordHasher hasher, JwtIssuer jwt,
                                 IdentityProperties properties,
                                 com.example.baseplate.security.jwt.TokenBlacklist blacklist) {
        this.dao = dao;
        this.hasher = hasher;
        this.jwt = jwt;
        this.properties = properties;
        this.blacklist = blacklist;
    }

    public AuthResult login(String username, String rawPassword, String tenant, String clientIp) {
        String tenantId = (tenant == null || tenant.isBlank()) ? properties.getDefaultTenant() : tenant;
        IdentityDao.UserRow user = dao.findUser(tenantId, username).orElse(null);
        if (user == null) {
            dao.insertLoginLog(tenantId, username, false, clientIp, "用户不存在");
            throw new AuthenticationException("用户名或密码错误");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(OffsetDateTime.now())) {
            dao.insertLoginLog(tenantId, username, false, clientIp, "账号锁定中");
            throw new AuthenticationException("账号已锁定，请稍后再试");
        }
        if (!hasher.matches(rawPassword, user.passwordHash())) {
            dao.recordLoginFailure(user.id(), properties.getMaxFailedAttempts(),
                    OffsetDateTime.now().plusMinutes(properties.getLockMinutes()));
            dao.insertLoginLog(tenantId, username, false, clientIp, "密码错误");
            throw new AuthenticationException("用户名或密码错误");
        }
        if (!ACTIVE.equals(user.status())) {
            dao.insertLoginLog(tenantId, username, false, clientIp, "账号状态异常");
            throw new AuthenticationException("账号不可用");
        }
        boolean mustChange = user.mustChangePassword() || passwordExpired(user);
        List<String> roleCodes = dao.rolesOf(user.id()).stream()
                .map(IdentityDao.RoleRow::roleCode).toList();
        Set<String> permissions = dao.permissionsOf(user.id());
        String token = jwt.issue(String.valueOf(user.id()), tenantId, user.username(),
                Set.copyOf(roleCodes), permissions, mustChange);
        dao.recordLoginSuccess(user.id());
        // 用有效凭证登录成功，说明管理员重置/改密带来的整体吊销已经完成使命，
        // 清掉标记，避免秒级时间戳把刚签发的令牌误杀
        blacklist.clearUserRevocation(String.valueOf(user.id()));
        dao.insertLoginLog(tenantId, username, true, clientIp, null);
        return new AuthResult(token, null, jwt.ttlSeconds(), mustChange);
    }

    private boolean passwordExpired(IdentityDao.UserRow user) {
        if (!properties.isPasswordExpireEnabled()) {
            return false;
        }
        if (user.passwordChangedAt() == null) {
            return true;
        }
        return user.passwordChangedAt().plusDays(properties.getPasswordExpireDays())
                .isBefore(OffsetDateTime.now());
    }
}
