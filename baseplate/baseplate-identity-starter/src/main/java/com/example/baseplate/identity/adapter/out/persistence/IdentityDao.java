package com.example.baseplate.identity.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * identity 的显式 SQL 入口：DDD 里属于适配器，只在 identity 模块内使用。
 * 所有查询显式带 tenant_id，禁止靠「上层应该传了」的假设。
 */
public final class IdentityDao {

    private final JdbcTemplate jdbc;

    public IdentityDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(Long id, String tenantId, String username, String passwordHash,
                          String displayName, Long orgId, String status, boolean mustChangePassword,
                          OffsetDateTime passwordChangedAt, int failedAttempts,
                          OffsetDateTime lockedUntil) {
    }

    public record RoleRow(Long id, String roleCode, String roleName, String dataScope, boolean builtIn) {
    }

    public record OrgRow(Long id, Long parentId, String name, String path) {
    }

    private static final RowMapper<UserRow> USER_MAPPER = (rs, i) -> new UserRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("username"),
            rs.getString("password_hash"), rs.getString("display_name"),
            (Long) rs.getObject("org_id"), rs.getString("status"),
            rs.getBoolean("must_change_password"),
            rs.getObject("password_changed_at", OffsetDateTime.class),
            rs.getInt("failed_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class));

    private static final RowMapper<RoleRow> ROLE_MAPPER = (rs, i) -> new RoleRow(
            rs.getLong("id"), rs.getString("role_code"), rs.getString("role_name"),
            rs.getString("data_scope"), rs.getBoolean("built_in"));

    public Optional<UserRow> findUser(String tenant, String username) {
        List<UserRow> rows = jdbc.query(
                "SELECT * FROM sys_user WHERE tenant_id = ? AND username = ?",
                USER_MAPPER, tenant, username);
        return rows.stream().findFirst();
    }

    public Optional<UserRow> findUserById(long id) {
        List<UserRow> rows = jdbc.query("SELECT * FROM sys_user WHERE id = ?", USER_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean usernameExists(String tenant, String username) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_user WHERE tenant_id = ? AND username = ?",
                Long.class, tenant, username);
        return count != null && count > 0;
    }

    public List<UserRow> searchUsers(String tenant, String keyword, List<Long> orgIds,
                                     Long onlyUserId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM sys_user WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username ILIKE ? OR display_name ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (onlyUserId != null) {
            sql.append(" AND id = ?");
            args.add(onlyUserId);
        } else if (orgIds != null && !orgIds.isEmpty()) {
            sql.append(" AND org_id IN (").append(placeholders(orgIds.size())).append(")");
            args.addAll(orgIds);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), USER_MAPPER, args.toArray());
    }

    public long countUsers(String tenant, String keyword, List<Long> orgIds, Long onlyUserId) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM sys_user WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username ILIKE ? OR display_name ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (onlyUserId != null) {
            sql.append(" AND id = ?");
            args.add(onlyUserId);
        } else if (orgIds != null && !orgIds.isEmpty()) {
            sql.append(" AND org_id IN (").append(placeholders(orgIds.size())).append(")");
            args.addAll(orgIds);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public long insertUser(String tenant, String username, String passwordHash, String displayName,
                           Long orgId, boolean mustChange) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sys_user(tenant_id, username, password_hash, display_name, org_id,"
                            + " status, must_change_password, password_changed_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, now())",
                    new String[]{"id"});
            ps.setString(1, tenant);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, displayName);
            if (orgId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, orgId);
            }
            ps.setBoolean(6, mustChange);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateUserBasic(long id, String displayName, Long orgId, String status) {
        jdbc.update("UPDATE sys_user SET display_name = ?, org_id = ?, status = ?, updated_at = now()"
                + " WHERE id = ?", displayName, orgId, status, id);
    }

    public void updatePassword(long id, String hash, boolean mustChange) {
        jdbc.update("UPDATE sys_user SET password_hash = ?, must_change_password = ?,"
                + " password_changed_at = now(), updated_at = now() WHERE id = ?",
                hash, mustChange, id);
    }

    public void recordLoginSuccess(long id) {
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL,"
                + " updated_at = now() WHERE id = ?", id);
    }

    /**
     * 管理员解锁：密码输错累积到阈值后，锁定检查在密码校验之前，
     * 用户即使改对了密码也进不来，只能由管理员清掉计数与锁定时点。
     */
    public void unlock(long id) {
        jdbc.update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL,"
                + " updated_at = now() WHERE id = ?", id);
    }

    public void recordLoginFailure(long id, int maxAttempts, OffsetDateTime lockUntil) {
        jdbc.update("UPDATE sys_user SET failed_attempts = failed_attempts + 1,"
                + " locked_until = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE locked_until END,"
                + " updated_at = now() WHERE id = ?", maxAttempts, lockUntil, id);
    }

    public void insertLoginLog(String tenant, String username, boolean success, String ip, String reason) {
        jdbc.update("INSERT INTO sys_login_log(tenant_id, username, success, client_ip, reason)"
                + " VALUES (?, ?, ?, ?, ?)", tenant, username, success, ip, reason);
    }

    public List<java.util.Map<String, Object>> recentLoginLogs(String tenant, int limit) {
        return jdbc.queryForList("SELECT id, username, success, client_ip, reason, created_at"
                + " FROM sys_login_log WHERE tenant_id = ? ORDER BY id DESC LIMIT ?", tenant, limit);
    }

    public List<RoleRow> rolesOf(long userId) {
        return jdbc.query("SELECT r.* FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id"
                + " WHERE ur.user_id = ? ORDER BY r.id", ROLE_MAPPER, userId);
    }

    public Set<String> permissionsOf(long userId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT rp.perm_code FROM sys_role_permission rp"
                        + " JOIN sys_user_role ur ON ur.role_id = rp.role_id WHERE ur.user_id = ?",
                String.class, userId));
    }

    public List<RoleRow> listRoles(String tenant) {
        return jdbc.query("SELECT * FROM sys_role WHERE tenant_id = ? ORDER BY id", ROLE_MAPPER, tenant);
    }

    public Optional<RoleRow> findRole(String tenant, long id) {
        List<RoleRow> rows = jdbc.query("SELECT * FROM sys_role WHERE tenant_id = ? AND id = ?",
                ROLE_MAPPER, tenant, id);
        return rows.stream().findFirst();
    }

    public long insertRole(String tenant, String code, String name, String dataScope) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sys_role(tenant_id, role_code, role_name, data_scope)"
                            + " VALUES (?, ?, ?, ?)", new String[]{"id"});
            ps.setString(1, tenant);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, dataScope);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateRole(long id, String name, String dataScope) {
        jdbc.update("UPDATE sys_role SET role_name = ?, data_scope = ? WHERE id = ?", name, dataScope, id);
    }

    public void deleteRole(long id) {
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id = ?", id);
        jdbc.update("DELETE FROM sys_user_role WHERE role_id = ?", id);
        jdbc.update("DELETE FROM sys_role WHERE id = ?", id);
    }

    public void replaceRolePermissions(long roleId, List<String> permissions) {
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id = ?", roleId);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO sys_role_permission(role_id, perm_code) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", roleId, permission);
        }
    }

    public List<String> rolePermissions(long roleId) {
        return jdbc.queryForList("SELECT perm_code FROM sys_role_permission WHERE role_id = ?"
                + " ORDER BY perm_code", String.class, roleId);
    }

    public void replaceUserRoles(long userId, List<Long> roleIds) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (Long roleId : roleIds) {
            jdbc.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING", userId, roleId);
        }
    }

    public List<Long> roleIdsOf(long userId) {
        return jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id = ?",
                Long.class, userId);
    }

    public List<OrgRow> listOrgs(String tenant) {
        return jdbc.query("SELECT id, parent_id, name, path FROM sys_org WHERE tenant_id = ?"
                + " ORDER BY sort_no, id", (rs, i) -> new OrgRow(
                rs.getLong("id"), (Long) rs.getObject("parent_id"),
                rs.getString("name"), rs.getString("path")), tenant);
    }

    public Optional<OrgRow> findOrg(String tenant, long id) {
        List<OrgRow> rows = jdbc.query("SELECT id, parent_id, name, path FROM sys_org"
                + " WHERE tenant_id = ? AND id = ?", (rs, i) -> new OrgRow(
                rs.getLong("id"), (Long) rs.getObject("parent_id"),
                rs.getString("name"), rs.getString("path")), tenant, id);
        return rows.stream().findFirst();
    }

    public long insertOrg(String tenant, Long parentId, String name, String path) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sys_org(tenant_id, parent_id, name, path) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, tenant);
            if (parentId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, parentId);
            }
            ps.setString(3, name);
            ps.setString(4, path);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateOrg(long id, String name) {
        jdbc.update("UPDATE sys_org SET name = ? WHERE id = ?", name, id);
    }

    public void updateOrgPath(long id, String path) {
        jdbc.update("UPDATE sys_org SET path = ? WHERE id = ?", path, id);
    }

    public void markRoleBuiltIn(long id) {
        jdbc.update("UPDATE sys_role SET built_in = TRUE WHERE id = ?", id);
    }

    public void deleteOrg(long id) {
        jdbc.update("DELETE FROM sys_org WHERE id = ?", id);
    }

    public List<Long> orgSubtreeIds(long orgId) {
        return jdbc.queryForList("SELECT child.id FROM sys_org child"
                + " JOIN sys_org parent ON parent.id = ?"
                + " WHERE child.path LIKE parent.path || '%'", Long.class, orgId);
    }

    public List<String> recentPasswordHashes(long userId, int limit) {
        return jdbc.queryForList("SELECT password_hash FROM sys_password_history"
                + " WHERE user_id = ? ORDER BY created_at DESC LIMIT ?", String.class, userId, limit);
    }

    public void insertPasswordHistory(long userId, String hash) {
        jdbc.update("INSERT INTO sys_password_history(user_id, password_hash) VALUES (?, ?)",
                userId, hash);
        jdbc.update("DELETE FROM sys_password_history WHERE user_id = ? AND id NOT IN"
                + " (SELECT id FROM sys_password_history WHERE user_id = ?"
                + " ORDER BY created_at DESC LIMIT 10)", userId, userId);
    }

    public long countUsersWithRole(String tenant, String roleCode) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM sys_user u"
                + " JOIN sys_user_role ur ON ur.user_id = u.id"
                + " JOIN sys_role r ON r.id = ur.role_id"
                + " WHERE u.tenant_id = ? AND r.role_code = ?", Long.class, tenant, roleCode);
        return count == null ? 0 : count;
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }
}
