package com.example.baseplate.identity.adapter.out.persistence;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.PermissionDef;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限目录仓储。
 *
 * <p>事实来源是数据库（sys_permission / sys_module），模块清单只负责「声明初始值」：
 * 启动时按清单 upsert，管理员在页面上改过的中文名不会被下次启动覆盖（label_edited 标记）。
 * 这样一来新增模块只要在 jar 里写 permissions，页面自动出现；基盘代码里不再维护模块中文名。</p>
 */
public final class PermissionCatalogRepository {

    private final JdbcTemplate jdbc;

    public PermissionCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ModuleRow(String moduleId, String label, String version, boolean enabled,
                            boolean labelEdited) {
    }

    public record PermissionRow(String moduleId, String moduleLabel, String code, String label,
                                String type, String source, boolean enabled, int sortNo,
                                boolean labelEdited) {
    }

    /** 把清单里的模块与权限点同步进库；已存在的只更新版本/类型/排序，不动管理员改过的名字。 */
    public void sync(List<ModuleDescriptor> modules) {
        List<String> declared = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            jdbc.update("INSERT INTO sys_module(module_id, label, version, enabled) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT (module_id) DO UPDATE SET"
                            + " label = CASE WHEN sys_module.label_edited THEN sys_module.label"
                            + "               ELSE EXCLUDED.label END,"
                            + " version = EXCLUDED.version, enabled = EXCLUDED.enabled",
                    module.id(), labelOf(module), module.version(), true);
            List<PermissionDef> defs = module.permissionDefs();
            for (int index = 0; index < defs.size(); index += 1) {
                PermissionDef def = defs.get(index);
                declared.add(def.code());
                jdbc.update("INSERT INTO sys_permission(module_id, code, label, type, source, sort_no)"
                                + " VALUES (?, ?, ?, ?, 'MANIFEST', ?)"
                                + " ON CONFLICT (code) DO UPDATE SET"
                                + " module_id = EXCLUDED.module_id,"
                                + " label = CASE WHEN sys_permission.label_edited THEN sys_permission.label"
                                + "               ELSE EXCLUDED.label END,"
                                + " type = EXCLUDED.type, sort_no = EXCLUDED.sort_no",
                        module.id(), def.code(), def.label(), def.type(), index);
            }
        }
        // 模块被摘掉或改名后，清掉库里已经没有声明的 MANIFEST 权限点（自定义的保留）
        if (declared.isEmpty()) {
            jdbc.update("UPDATE sys_permission SET enabled = FALSE WHERE source = 'MANIFEST'");
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(declared.size(), "?"));
            List<Object> args = new ArrayList<>(declared);
            jdbc.update("UPDATE sys_permission SET enabled = FALSE"
                    + " WHERE source = 'MANIFEST' AND code NOT IN (" + placeholders + ")", args.toArray());
        }
    }

    private String labelOf(ModuleDescriptor module) {
        return module.displayName() == null ? module.id() : module.displayName();
    }

    public List<ModuleRow> modules() {
        return jdbc.query("SELECT module_id, label, version, enabled, label_edited"
                        + " FROM sys_module ORDER BY module_id",
                (rs, i) -> new ModuleRow(rs.getString("module_id"), rs.getString("label"),
                        rs.getString("version"), rs.getBoolean("enabled"),
                        rs.getBoolean("label_edited")));
    }

    public List<PermissionRow> permissions() {
        return jdbc.query("SELECT p.module_id, m.label AS module_label, p.code, p.label, p.type,"
                        + " p.source, p.enabled, p.sort_no, p.label_edited"
                        + " FROM sys_permission p LEFT JOIN sys_module m ON m.module_id = p.module_id"
                        + " ORDER BY p.module_id, p.sort_no, p.id",
                (rs, i) -> new PermissionRow(rs.getString("module_id"), rs.getString("module_label"),
                        rs.getString("code"), rs.getString("label"), rs.getString("type"),
                        rs.getString("source"), rs.getBoolean("enabled"), rs.getInt("sort_no"),
                        rs.getBoolean("label_edited")));
    }

    public boolean exists(String code) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sys_permission WHERE code = ?",
                Integer.class, code);
        return count != null && count > 0;
    }

    public boolean moduleExists(String moduleId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sys_module WHERE module_id = ?",
                Integer.class, moduleId);
        return count != null && count > 0;
    }

    /** 页面上手工补的权限点：source=CUSTOM，删除时只允许删这一类。 */
    public void insertCustom(String moduleId, String code, String label, String type) {
        jdbc.update("INSERT INTO sys_permission(module_id, code, label, type, source, sort_no)"
                        + " VALUES (?, ?, ?, ?, 'CUSTOM', 999)",
                moduleId, code, label, type);
    }

    public void updatePermission(String code, String label, String type) {
        jdbc.update("UPDATE sys_permission SET label = ?, type = ?, label_edited = TRUE,"
                + " updated_at = now() WHERE code = ?", label, type, code);
    }

    public void setEnabled(String code, boolean enabled) {
        jdbc.update("UPDATE sys_permission SET enabled = ?, updated_at = now() WHERE code = ?",
                enabled, code);
    }

    public String sourceOf(String code) {
        List<String> rows = jdbc.queryForList(
                "SELECT source FROM sys_permission WHERE code = ?", String.class, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean deleteCustom(String code) {
        return jdbc.update("DELETE FROM sys_permission WHERE code = ? AND source = 'CUSTOM'", code) > 0;
    }

    public void renameModule(String moduleId, String label) {
        jdbc.update("UPDATE sys_module SET label = ?, label_edited = TRUE, updated_at = now()"
                + " WHERE module_id = ?", label, moduleId);
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sys_permission", Integer.class);
        return count == null ? 0 : count;
    }
}
