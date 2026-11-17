package com.example.baseplate.identity.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.identity.application.OrgService;
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

/** 组织架构：部门树，与数据权限联动。 */
@RestController
@RequestMapping("/api/identity/orgs")
public class OrgController {

    private final OrgService service;

    public OrgController(OrgService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("org:view")
    public List<OrgService.OrgNode> list() {
        return service.list();
    }

    @PostMapping
    @RequiresPermission("org:create")
    @AuditLog(action = "org:create", resource = "sys_org")
    public Map<String, Object> create(@RequestBody OrgRequest request) {
        return Map.of("id", service.create(request.parentId(), request.name()));
    }

    @PutMapping("/{id}")
    @RequiresPermission("org:update")
    @AuditLog(action = "org:update", resource = "sys_org")
    public Map<String, Object> rename(@PathVariable long id, @RequestBody OrgRequest request) {
        service.rename(id, request.name());
        return Map.of("updated", true);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("org:delete")
    @AuditLog(action = "org:delete", resource = "sys_org")
    public Map<String, Object> delete(@PathVariable long id) {
        service.delete(id);
        return Map.of("deleted", true);
    }

    public record OrgRequest(Long parentId, String name) {
    }
}
