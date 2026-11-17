package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.platform.adapter.out.persistence.OpenApiCatalogRepository;
import com.example.baseplate.platform.domain.OpenApiDef;
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
import java.util.List;
import java.util.Map;

/**
 * 全局开放 API 目录：所有应用可授权的接口清单。
 *
 * <p>目录主体由代码里的 {@code @OpenApi} 扫描产生，页面只能改展示文案与启停；
 * 另有「手工登记」用于接口不在本进程内的情况（走网关转发的既有系统）。
 * 方法、路径、主体类型、所需权限一律不接受页面修改——这些是安全边界。</p>
 */
@RestController
@RequestMapping("/api/platform/apis")
public class PlatformApiCatalogController {

    private final OpenApiCatalogRepository repository;

    public PlatformApiCatalogController(OpenApiCatalogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @RequiresPermission("openapi:view")
    public Map<String, Object> catalog() {
        List<OpenApiDef> items = repository.all();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("groups", distinctGroups(items));
        return result;
    }

    @PostMapping
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:register-api", resource = "open_api")
    public Map<String, Object> register(@RequestBody ManualApiRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new IllegalArgumentException("接口编码不能为空");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("接口名称不能为空");
        }
        if (request.method() == null || request.path() == null) {
            throw new IllegalArgumentException("方法与路径不能为空");
        }
        String method = request.method().trim().toUpperCase();
        if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) {
            throw new IllegalArgumentException("不支持的方法：" + method);
        }
        OpenApiDef def = new OpenApiDef(request.code().trim(), request.name().trim(), method,
                request.path().trim(),
                request.groupName() == null || request.groupName().isBlank() ? "手工登记" : request.groupName().trim(),
                request.description() == null || request.description().isBlank() ? null : request.description().trim(),
                request.subject() == null ? OpenApiSubject.CLIENT : request.subject(),
                request.requiredPermission(), request.requiredScope(),
                "MANUAL", "manual",
                request.sandboxAllowed() == null || request.sandboxAllowed(), false, true);
        repository.manualRegister(def);
        return Map.of("created", true, "code", def.code());
    }

    /** 只能改展示文案；改过后启动同步不会再覆盖。 */
    @PutMapping("/{code}")
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:relabel-api", resource = "open_api")
    public Map<String, Object> relabel(@PathVariable String code,
                                       @RequestBody RelabelRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("接口名称不能为空");
        }
        repository.relabel(code, request.name().trim(), request.groupName(), request.description());
        return Map.of("updated", true);
    }

    @PostMapping("/{code}/enabled")
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:toggle-api", resource = "open_api")
    public Map<String, Object> toggle(@PathVariable String code, @RequestParam boolean value) {
        repository.setEnabled(code, value);
        return Map.of("enabled", value);
    }

    @DeleteMapping("/{code}")
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:delete-api", resource = "open_api")
    public Map<String, Object> delete(@PathVariable String code) {
        repository.deleteManual(code);
        return Map.of("deleted", true);
    }

    private List<String> distinctGroups(List<OpenApiDef> items) {
        List<String> groups = new ArrayList<>();
        for (OpenApiDef def : items) {
            String group = def.groupName() == null || def.groupName().isBlank()
                    ? "未分组" : def.groupName();
            if (!groups.contains(group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    public record ManualApiRequest(String code, String name, String method, String path,
                                   String groupName, String description,
                                   OpenApiSubject subject, String requiredPermission,
                                   String requiredScope, Boolean sandboxAllowed) {
    }

    public record RelabelRequest(String name, String groupName, String description) {
    }
}
