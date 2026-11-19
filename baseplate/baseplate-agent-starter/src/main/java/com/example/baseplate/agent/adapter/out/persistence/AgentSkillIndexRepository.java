package com.example.baseplate.agent.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 技能索引：正文在本地 skills 目录，这里只存元数据、校验和与有效性。
 * 全局目录用 tenant_id='*'，以后需要租户隔离时按目录前缀扩展即可。
 */
public final class AgentSkillIndexRepository {

    private final JdbcTemplate jdbc;

    public AgentSkillIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SkillRow(String skillName, String description, String path, String version,
                           String checksum, boolean enabled, String status,
                           OffsetDateTime lastScanAt) {
    }

    private static final RowMapper<SkillRow> MAPPER = (rs, i) -> new SkillRow(
            rs.getString("skill_name"),
            rs.getString("description"),
            rs.getString("path"),
            rs.getString("version"),
            rs.getString("checksum"),
            rs.getBoolean("enabled"),
            rs.getString("status"),
            rs.getObject("last_scan_at", OffsetDateTime.class));

    public void upsert(String tenant, String name, String description, String path,
                       String version, String checksum) {
        jdbc.update("""
                INSERT INTO agent_skill_index
                    (tenant_id, skill_name, description, path, version, checksum, enabled, status, last_scan_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, 'VALID', now())
                ON CONFLICT (tenant_id, skill_name)
                DO UPDATE SET description = EXCLUDED.description,
                              path = EXCLUDED.path,
                              version = EXCLUDED.version,
                              checksum = EXCLUDED.checksum,
                              status = 'VALID',
                              last_scan_at = now()
                """, tenant, name, description, path, version, checksum);
    }

    /** 目录里已删除的技能标成 MISSING，不物理删索引，便于排查。 */
    public void markMissing(String tenant, List<String> presentNames) {
        if (presentNames.isEmpty()) {
            jdbc.update("""
                    UPDATE agent_skill_index SET status = 'MISSING', last_scan_at = now()
                     WHERE tenant_id = ?
                    """, tenant);
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(presentNames.size(), "?"));
        Object[] args = new Object[presentNames.size() + 1];
        args[0] = tenant;
        for (int i = 0; i < presentNames.size(); i++) {
            args[i + 1] = presentNames.get(i);
        }
        jdbc.update("""
                UPDATE agent_skill_index SET status = 'MISSING', last_scan_at = now()
                 WHERE tenant_id = ? AND skill_name NOT IN (%s)
                """.formatted(placeholders), args);
    }

    public List<SkillRow> list(String tenant) {
        return jdbc.query("""
                SELECT * FROM agent_skill_index
                 WHERE tenant_id IN ('*', ?)
                 ORDER BY status, skill_name
                """, MAPPER, tenant);
    }

    public java.util.Optional<SkillRow> find(String tenant, String skillName) {
        return jdbc.query("""
                SELECT * FROM agent_skill_index
                 WHERE tenant_id IN ('*', ?) AND skill_name = ?
                 LIMIT 1
                """, MAPPER, tenant, skillName).stream().findFirst();
    }

    /**
     * 主动删除技能时物理移除索引。
     * MISSING 只用于「文件被手工删掉」的诊断，用户在页面点删除后不该继续留垃圾行。
     */
    public void delete(String tenant, String skillName) {
        jdbc.update("""
                DELETE FROM agent_skill_index
                 WHERE tenant_id IN ('*', ?) AND skill_name = ?
                """, tenant, skillName);
    }
}
