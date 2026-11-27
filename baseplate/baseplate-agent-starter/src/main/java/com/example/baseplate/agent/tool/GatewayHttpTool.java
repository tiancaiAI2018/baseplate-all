package com.example.baseplate.agent.tool;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.ExternalToolDescriptor;
import com.example.baseplate.api.port.out.ExternalServiceGateway;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 平台外部服务的只读工具：Agent 只传参数，URL 与凭证由平台网关解析。
 * 有副作用的端点不会生成这个工具，而是注册为 EXTERNAL 由前端执行。
 */
public final class GatewayHttpTool extends ToolBase {

    private final ExternalToolDescriptor descriptor;
    private final ExternalServiceGateway gateway;

    public GatewayHttpTool(ExternalToolDescriptor descriptor, ExternalServiceGateway gateway) {
        this(descriptor.code(), descriptor.serviceCode(), descriptor.endpointCode(),
                descriptor.description(), descriptor.paramsSchema(),
                descriptor.permissionCode(), gateway);
    }

    /** 控制台创建的端点绑定工具：只绑定 serviceCode/endpointCode，URL 与凭证仍由网关注入。 */
    public GatewayHttpTool(String toolCode, String serviceCode, String endpointCode,
                           String description, Map<String, Object> paramsSchema,
                           String permissionCode, ExternalServiceGateway gateway) {
        super(ToolBase.builder()
                .name(toolCode)
                .description(description == null || description.isBlank() ? toolCode : description)
                .inputSchema(paramsSchema == null || paramsSchema.isEmpty()
                        ? Map.of("type", "object", "properties", Map.of()) : paramsSchema)
                .readOnly(true)
                .concurrencySafe(true));
        this.descriptor = new ExternalToolDescriptor(toolCode, toolCode, description,
                serviceCode, endpointCode, true, paramsSchema, java.util.Set.of("*"), permissionCode);
        this.gateway = gateway;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(ToolCallParam param) {
        CurrentUser user = param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get("principal");
        requirePermission(user);
        String tenant = user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = param.getInput() == null ? Map.of() : (Map<String, Object>) param.getInput();
            String body = gateway.call(tenant, descriptor.serviceCode(), descriptor.endpointCode(),
                    input, Map.of());
            return ToolResultBlock.text(body);
        } catch (Exception e) {
            return ToolResultBlock.error("调用 " + descriptor.code() + " 失败: " + e.getMessage());
        }
    }

    private void requirePermission(CurrentUser user) {
        String code = descriptor.permissionCode();
        if (code == null || code.isBlank()) {
            return;
        }
        if (user == null) {
            throw new IllegalStateException("缺少用户上下文");
        }
        if (user.roles() != null && user.roles().contains("SUPER_ADMIN")) {
            return;
        }
        if (user.permissions() == null || !user.permissions().contains(code)) {
            throw new IllegalStateException("当前用户缺少权限: " + code);
        }
    }
}
