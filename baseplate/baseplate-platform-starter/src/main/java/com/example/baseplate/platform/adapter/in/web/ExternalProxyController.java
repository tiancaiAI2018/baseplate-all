package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ExternalServiceGateway;
import com.example.baseplate.platform.application.ExternalServiceRegistry;
import com.example.baseplate.platform.domain.ExternalEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部工具的浏览器执行通道：前端带基盘用户令牌调用本接口，
 * 服务端注入第三方凭证并转发。凭证永远不下发浏览器。
 */
@RestController
@RequestMapping("/api/integration/proxy")
public class ExternalProxyController {

    private static final Logger log = LoggerFactory.getLogger(ExternalProxyController.class);

    private final ExternalServiceGateway gateway;
    private final ExternalServiceRegistry registry;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalProxyController(ExternalServiceGateway gateway,
                                   ExternalServiceRegistry registry,
                                   CurrentUserProvider currentUser) {
        this.gateway = gateway;
        this.registry = registry;
        this.currentUser = currentUser;
    }

    /** 平台已注册的外部服务端点：工具配置页展示用，凭证不会返回。 */
    @GetMapping("/services")
    public Object services() {
        return registry.descriptors();
    }

    @PostMapping("/{serviceCode}/{endpointCode}")
    public Object proxy(@PathVariable String serviceCode,
                        @PathVariable String endpointCode,
                        @RequestBody(required = false) Map<String, Object> params,
                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        CurrentUser user = currentUser.current();
        String tenant = user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
        ExternalEndpoint endpoint = registry.endpoint(serviceCode, endpointCode);
        if (endpoint == null) {
            throw new IllegalArgumentException("外部端点不存在: " + serviceCode + "/" + endpointCode);
        }
        requirePermission(user, endpoint.permissionCode());

        Map<String, String> headers = new LinkedHashMap<>();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        String body = gateway.call(tenant, serviceCode, endpointCode,
                params == null ? Map.of() : params, headers);
        log.info("外部工具代理调用 service={} endpoint={} user={} size={}",
                serviceCode, endpointCode, user == null ? "-" : user.userId(), body.length());
        try {
            return mapper.readValue(body, Object.class);
        } catch (Exception e) {
            return Map.of("raw", body);
        }
    }

    private static void requirePermission(CurrentUser user, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank() || user == null) {
            return;
        }
        if (user.roles() != null && user.roles().contains("SUPER_ADMIN")) {
            return;
        }
        if (user.permissions() == null || !user.permissions().contains(permissionCode)) {
            throw new IllegalStateException("当前用户缺少权限: " + permissionCode);
        }
    }
}
