package com.example.baseplate.identity.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.MenuDef;
import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.PermissionDef;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.identity.adapter.out.persistence.PermissionCatalogRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限点与菜单。
 *
 * <p>权限目录的事实来源是数据库 sys_permission：模块在 jar 的 baseplate-module.yml 里声明初始值，
 * 启动时由 {@code PermissionCatalogSyncRunner} 同步进库；管理员可以在页面上改中文名、补自定义权限点、
 * 停用废弃权限点。新增业务模块不需要改基盘代码，也不需要手工插数据。</p>
 *
 * <p>只有「库里一条都没有」时才回退到内存清单，用于表还没建或同步未跑的兜底。</p>
 */
@RestController
@RequestMapping("/api/identity")
public class PermissionController {

    private final ModuleRegistry moduleRegistry;
    private final PermissionCatalogRepository catalog;
    private final CurrentUserProvider currentUser;

    public PermissionController(ModuleRegistry moduleRegistry, PermissionCatalogRepository catalog,
                                CurrentUserProvider currentUser) {
        this.moduleRegistry = moduleRegistry;
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        List<PermissionCatalogRepository.PermissionRow> rows = catalog.permissions();
        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return fallbackFromManifests(result);
        }
        Set<String> codes = new LinkedHashSet<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byModule = new LinkedHashMap<>();
        for (PermissionCatalogRepository.PermissionRow row : rows) {
            if (row.enabled()) {
                codes.add(row.code());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", row.code());
            item.put("label", row.label());
            item.put("type", row.type());
            item.put("source", row.source());
            item.put("enabled", row.enabled());
            item.put("module", row.moduleId());
            // 停用的权限点只出现在「权限点管理」里，不进角色配置树
            if (row.enabled()) {
                byModule.computeIfAbsent(row.moduleId(), key -> new ArrayList<>()).add(item);
            }
        }
        for (PermissionCatalogRepository.ModuleRow module : catalog.modules()) {
            List<Map<String, Object>> items = byModule.get(module.moduleId());
            if (items == null || items.isEmpty()) {
                continue;
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("module", module.moduleId());
            group.put("label", module.label());
            group.put("version", module.version());
            group.put("permissions", items);
            groups.add(group);
        }
        List<Map<String, Object>> manage = new ArrayList<>();
        for (PermissionCatalogRepository.PermissionRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("module", row.moduleId());
            item.put("moduleLabel", row.moduleLabel());
            item.put("code", row.code());
            item.put("label", row.label());
            item.put("type", row.type());
            item.put("source", row.source());
            item.put("enabled", row.enabled());
            item.put("labelEdited", row.labelEdited());
            manage.add(item);
        }
        result.put("codes", codes);
        result.put("groups", groups);
        result.put("manage", manage);
        result.put("modules", catalog.modules());
        return result;
    }

    /** 表里没数据时用内存清单兜底，至少页面不是空的。 */
    private Map<String, Object> fallbackFromManifests(Map<String, Object> result) {
        List<ModuleDescriptor> modules = moduleRegistry.loaded();
        Set<String> codes = new LinkedHashSet<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            codes.addAll(module.permissions());
            List<PermissionDef> defs = module.permissionDefs();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("module", module.id());
            group.put("label", module.displayName());
            group.put("version", module.version());
            group.put("permissions", defs);
            groups.add(group);
        }
        result.put("codes", codes);
        result.put("groups", groups);
        result.put("manage", List.of());
        result.put("modules", modules.stream().map(module -> Map.of(
                "moduleId", module.id(), "label", module.displayName(),
                "version", module.version())).toList());
        return result;
    }

    public record PermissionRequest(String moduleId, String code, String label, String type) {
    }

    public record PermissionUpdate(String label, String type) {
    }

    public record ModuleRename(String label) {
    }

    @PostMapping("/permissions")
    @RequiresPermission("role:assign")
    @AuditLog(action = "identity:permission-create", resource = "sys_permission")
    public Map<String, Object> createPermission(@RequestBody PermissionRequest request) {
        String code = requireCode(request.code(), "权限编码");
        String label = requireText(request.label(), "权限名称");
        String moduleId = requireCode(request.moduleId(), "所属模块");
        if (!catalog.moduleExists(moduleId)) {
            throw new IllegalArgumentException("模块不存在：" + moduleId);
        }
        if (catalog.exists(code)) {
            throw new IllegalArgumentException("权限编码已存在：" + code);
        }
        String type = "MENU".equalsIgnoreCase(request.type()) ? "MENU" : "BUTTON";
        catalog.insertCustom(moduleId, code, label, type);
        return Map.of("code", code, "source", "CUSTOM");
    }

    @PutMapping("/permissions/{code}")
    @RequiresPermission("role:assign")
    @AuditLog(action = "identity:permission-update", resource = "sys_permission")
    public Map<String, Object> updatePermission(@PathVariable String code,
                                               @RequestBody PermissionUpdate update) {
        if (!catalog.exists(code)) {
            throw new IllegalArgumentException("权限点不存在：" + code);
        }
        String label = requireText(update.label(), "权限名称");
        String type = "MENU".equalsIgnoreCase(update.type()) ? "MENU" : "BUTTON";
        catalog.updatePermission(code, label, type);
        return Map.of("code", code, "label", label, "type", type);
    }

    @PostMapping("/permissions/{code}/enabled")
    @RequiresPermission("role:assign")
    @AuditLog(action = "identity:permission-toggle", resource = "sys_permission")
    public Map<String, Object> togglePermission(@PathVariable String code,
                                               @RequestParam boolean value) {
        if (!catalog.exists(code)) {
            throw new IllegalArgumentException("权限点不存在：" + code);
        }
        catalog.setEnabled(code, value);
        return Map.of("code", code, "enabled", value);
    }

    @DeleteMapping("/permissions/{code}")
    @RequiresPermission("role:assign")
    @AuditLog(action = "identity:permission-delete", resource = "sys_permission")
    public Map<String, Object> deletePermission(@PathVariable String code) {
        String source = catalog.sourceOf(code);
        if (source == null) {
            throw new IllegalArgumentException("权限点不存在：" + code);
        }
        // 模块声明的权限点由清单管理，下次启动会被重新同步回来，删了没意义
        if (!"CUSTOM".equals(source)) {
            throw new IllegalArgumentException("模块声明的权限点不能删除，请改为停用");
        }
        catalog.deleteCustom(code);
        return Map.of("deleted", true);
    }

    @PutMapping("/modules/{moduleId}")
    @RequiresPermission("role:assign")
    @AuditLog(action = "identity:module-rename", resource = "sys_module")
    public Map<String, Object> renameModule(@PathVariable String moduleId,
                                            @RequestBody ModuleRename rename) {
        if (!catalog.moduleExists(moduleId)) {
            throw new IllegalArgumentException("模块不存在：" + moduleId);
        }
        String label = requireText(rename.label(), "模块名称");
        catalog.renameModule(moduleId, label);
        return Map.of("moduleId", moduleId, "label", label);
    }

    @GetMapping("/menus")
    public List<MenuDef> menus() {
        CurrentUser user = currentUser.current();
        boolean superAdmin = user.roles().contains("SUPER_ADMIN");
        return moduleRegistry.loaded().stream()
                .flatMap(module -> module.menus().stream())
                .filter(menu -> superAdmin || menu.permission() == null
                        || user.permissions().contains(menu.permission()))
                .toList();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException(field + "过长");
        }
        return trimmed;
    }

    /** 编码要能当路径段/配置键用，不能带空白和斜杠。 */
    private String requireCode(String value, String field) {
        String trimmed = requireText(value, field);
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException(field + "过长（上限 64）");
        }
        if (trimmed.chars().anyMatch(c -> Character.isWhitespace(c) || c == '/')) {
            throw new IllegalArgumentException(field + "不能包含空白或斜杠");
        }
        return trimmed;
    }
}
