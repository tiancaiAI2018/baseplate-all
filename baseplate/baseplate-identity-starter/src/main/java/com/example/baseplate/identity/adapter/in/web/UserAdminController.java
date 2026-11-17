package com.example.baseplate.identity.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.identity.application.UserAdminService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** 用户管理：增删改查、重置密码、分配角色。 */
@RestController
@RequestMapping("/api/identity/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("user:view")
    public PageResult<UserAdminService.UserView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, Math.max(1, page), Math.min(200, Math.max(1, size)));
    }

    @PostMapping
    @RequiresPermission("user:create")
    @AuditLog(action = "user:create", resource = "sys_user")
    public UserAdminService.UserView create(@RequestBody UserAdminService.CreateCommand command) {
        return service.create(command);
    }

    @PutMapping("/{id}")
    @RequiresPermission("user:update")
    @AuditLog(action = "user:update", resource = "sys_user")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody UserAdminService.UpdateCommand command) {
        service.update(id, command);
        return Map.of("updated", true);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("user:delete")
    @AuditLog(action = "user:disable", resource = "sys_user")
    public Map<String, Object> disable(@PathVariable long id) {
        service.disable(id);
        return Map.of("disabled", true);
    }

    @PostMapping("/{id}/reset-password")
    @RequiresPermission("user:reset")
    @AuditLog(action = "user:reset-password", resource = "sys_user")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        String password = service.resetPassword(id, body == null ? null : body.get("password"));
        return Map.of("password", password, "mustChangePassword", true);
    }

    @PostMapping("/{id}/unlock")
    @RequiresPermission("user:reset")
    @AuditLog(action = "user:unlock", resource = "sys_user")
    public Map<String, Object> unlock(@PathVariable long id) {
        service.unlock(id);
        return Map.of("unlocked", true);
    }

    @PostMapping("/{id}/roles")
    @RequiresPermission("role:assign")
    @AuditLog(action = "user:assign-roles", resource = "sys_user")
    public Map<String, Object> assignRoles(@PathVariable long id, @RequestBody List<Long> roleIds) {
        service.assignRoles(id, roleIds);
        return Map.of("assigned", true);
    }
}
