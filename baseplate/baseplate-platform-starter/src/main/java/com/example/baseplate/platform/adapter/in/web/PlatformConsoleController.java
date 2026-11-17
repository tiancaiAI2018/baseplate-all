package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.platform.application.OpenAppService;
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

/** 开发平台控制台后端：应用管理、密钥轮换、用量、调用日志、沙盒测试。 */
@RestController
@RequestMapping("/api/platform/apps")
public class PlatformConsoleController {

    private final OpenAppService service;

    public PlatformConsoleController(OpenAppService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("openapi:view")
    public List<OpenAppService.AppView> list() {
        return service.list();
    }

    @PostMapping
    @RequiresPermission("openapi:create")
    @AuditLog(action = "openapi:create-app", resource = "open_app")
    public OpenAppService.CreatedApp create(@RequestBody AppRequest request) {
        return service.create(request.toCommand());
    }

    @PutMapping("/{id}")
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:update-app", resource = "open_app")
    public OpenAppService.AppView update(@PathVariable long id, @RequestBody AppRequest request) {
        return service.update(id, request.toCommand());
    }

    @PostMapping("/{id}/rotate")
    @RequiresPermission("openapi:rotate")
    @AuditLog(action = "openapi:rotate-secret", resource = "open_app")
    public Map<String, Object> rotate(@PathVariable long id) {
        return service.rotate(id);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:delete-app", resource = "open_app")
    public Map<String, Object> delete(@PathVariable long id) {
        service.delete(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/{id}/apis")
    @RequiresPermission("openapi:view")
    public Map<String, Object> apis(@PathVariable long id) {
        return service.apis(id);
    }

    @PutMapping("/{id}/apis")
    @RequiresPermission("openapi:update")
    @AuditLog(action = "openapi:grant-apis", resource = "open_app_api")
    public Map<String, Object> grantApis(@PathVariable long id, @RequestBody ApiGrantRequest request) {
        service.grantApis(id, request.codes());
        return Map.of("updated", true);
    }

    @GetMapping("/{id}/logs/{logId}")
    @RequiresPermission("openapi:log")
    public Map<String, Object> logDetail(@PathVariable long id, @PathVariable long logId) {
        return service.logDetail(id, logId);
    }

    @GetMapping("/{id}/logs")
    @RequiresPermission("openapi:log")
    public List<Map<String, Object>> logs(@PathVariable long id,
                                          @RequestParam(defaultValue = "50") int limit) {
        return service.logs(id, limit);
    }

    @GetMapping("/{id}/usage")
    @RequiresPermission("openapi:view")
    public List<Map<String, Object>> usage(@PathVariable long id,
                                           @RequestParam(defaultValue = "7") int days) {
        return service.usage(id, days);
    }

    @PostMapping("/{id}/sandbox-test")
    @RequiresPermission("openapi:view")
    public Map<String, Object> sandboxTest(@PathVariable long id,
                                           @RequestBody SandboxRequest request) {
        return service.sandboxTest(id, request.method(), request.path(), request.body());
    }

    /**
     * 布尔与数字一律用包装类型：Jackson 3 在 FAIL_ON_NULL_FOR_PRIMITIVES 下，
     * 请求体里缺字段或显式传 null 会直接解析失败，页面就会看到「服务内部错误」。
     */
    public record AppRequest(String appName, String authMode, List<String> grantTypes,
                             List<String> scopes, String ipWhitelist, Integer qpsLimit,
                             Integer dayLimit, String status, Boolean sandbox,
                             List<String> redirectUris) {

        OpenAppService.AppCommand toCommand() {
            return new OpenAppService.AppCommand(appName, authMode, grantTypes, scopes,
                    ipWhitelist,
                    qpsLimit == null ? 1 : qpsLimit,
                    dayLimit == null ? 1 : dayLimit,
                    sandbox == null || sandbox, redirectUris, status);
        }
    }

    public record SandboxRequest(String method, String path, Map<String, Object> body) {
    }

    public record ApiGrantRequest(java.util.List<String> codes) {
    }
}
