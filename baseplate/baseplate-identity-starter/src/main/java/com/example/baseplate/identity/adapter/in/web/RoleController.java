package com.example.baseplate.identity.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.identity.application.RoleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 角色管理：角色—权限绑定、数据范围。 */
@RestController
@RequestMapping("/api/identity/roles")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("role:view")
    public List<RoleService.RoleView> list() {
        return service.list();
    }

    @PostMapping
    @RequiresPermission("role:create")
    @AuditLog(action = "role:create", resource = "sys_role")
    public Map<String, Object> create(@RequestBody RoleRequest request) {
        long id = service.create(request.roleCode(), request.roleName(), request.dataScope(),
                request.permissions());
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    @RequiresPermission("role:update")
    @AuditLog(action = "role:update", resource = "sys_role")
    public Map<String, Object> update(@PathVariable long id, @RequestBody RoleRequest request) {
        service.update(id, request.roleName(), request.dataScope());
        return Map.of("updated", true);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("role:delete")
    @AuditLog(action = "role:delete", resource = "sys_role")
    public Map<String, Object> delete(@PathVariable long id) {
        service.delete(id);
        return Map.of("deleted", true);
    }

    @PutMapping("/{id}/permissions")
    @RequiresPermission("role:assign")
    @AuditLog(action = "role:set-permissions", resource = "sys_role")
    public Map<String, Object> setPermissions(@PathVariable long id, @RequestBody List<String> permissions) {
        service.setPermissions(id, permissions);
        return Map.of("updated", true);
    }

    public record RoleRequest(String roleCode, String roleName, String dataScope,
                              List<String> permissions) {
    }
}
