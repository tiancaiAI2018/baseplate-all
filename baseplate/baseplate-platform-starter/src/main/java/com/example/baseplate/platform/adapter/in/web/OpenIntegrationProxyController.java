package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.OpenApi;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ExternalServiceGateway;
import com.example.baseplate.platform.application.ExternalServiceRegistry;
import com.example.baseplate.platform.domain.ExternalEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 开放平台上的外部工具执行通道。
 *
 * <p>和内部 {@link ExternalProxyController} 走同一套网关：URL 与凭证只在服务端解析，
 * 第三方只传 serviceCode/endpointCode/参数。主体由 OpenApiAuthFilter 写入，
 * 所以 {@code subject=USER} 的调用必须代表真实用户。</p>
 */
@RestController
@RequestMapping("/openapi/integration/proxy")
public class OpenIntegrationProxyController {

    private static final Logger log = LoggerFactory.getLogger(OpenIntegrationProxyController.class);

    private final ExternalServiceGateway gateway;
    private final ExternalServiceRegistry registry;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenIntegrationProxyController(ExternalServiceGateway gateway,
                                          ExternalServiceRegistry registry,
                                          CurrentUserProvider currentUser) {
        this.gateway = gateway;
        this.registry = registry;
        this.currentUser = currentUser;
    }

    @PostMapping("/{serviceCode}/{endpointCode}")
    @OpenApi(code = "integration.open.proxy", name = "外部工具执行（开放平台）", group = "集成",
            subject = OpenApiSubject.USER, permission = "agent:chat", scope = "write")
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
        log.info("开放平台外部工具调用 service={} endpoint={} user={} size={}",
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
