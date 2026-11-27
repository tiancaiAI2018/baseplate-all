package com.example.baseplate.agent.tool;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.api.model.ExternalToolDescriptor;
import com.example.baseplate.api.port.out.ExternalServiceGateway;
import com.example.baseplate.api.port.out.ExternalToolDescriptorProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 把平台注册表的端点投影成 Agent 工具：
 * 只读 → CUSTOM_INTERNAL（服务端经网关注入凭证）
 * 有副作用 → EXTERNAL（前端执行，经基盘代理注入凭证）
 */
public final class ExternalServiceToolProvider implements AgentToolProvider {

    private final ObjectProvider<ExternalToolDescriptorProvider> descriptorProviders;
    private final ExternalServiceGateway gateway;

    public ExternalServiceToolProvider(ObjectProvider<ExternalToolDescriptorProvider> descriptorProviders,
                                       ExternalServiceGateway gateway) {
        this.descriptorProviders = descriptorProviders;
        this.gateway = gateway;
    }

    @Override
    public List<AgentToolSpec> tools() {
        List<AgentToolSpec> result = new ArrayList<>();
        for (ExternalToolDescriptorProvider provider : descriptorProviders.orderedStream().toList()) {
            for (ExternalToolDescriptor descriptor : provider.descriptors()) {
                ToolType type = descriptor.readonly() ? ToolType.CUSTOM_INTERNAL : ToolType.EXTERNAL;
                ToolDescriptor tool = new ToolDescriptor(
                        descriptor.code(), descriptor.name(), descriptor.description(), type,
                        false, false, descriptor.pages(), descriptor.permissionCode(),
                        descriptor.readonly() ? RiskLevel.READ : RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        descriptor.paramsSchema() == null ? java.util.Map.of() : descriptor.paramsSchema());
                result.add(new AgentToolSpec(tool,
                        descriptor.readonly() ? new GatewayHttpTool(descriptor, gateway) : null));
            }
        }
        return result;
    }
}
