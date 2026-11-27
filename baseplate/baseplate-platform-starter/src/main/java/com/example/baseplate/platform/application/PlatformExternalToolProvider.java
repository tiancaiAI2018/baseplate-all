package com.example.baseplate.platform.application;

import com.example.baseplate.api.model.ExternalToolDescriptor;
import com.example.baseplate.api.port.out.ExternalToolDescriptorProvider;

import java.util.List;

/** 平台侧实现：把注册表里的端点投影成 Agent 工具描述。 */
public final class PlatformExternalToolProvider implements ExternalToolDescriptorProvider {

    private final ExternalServiceRegistry registry;

    public PlatformExternalToolProvider(ExternalServiceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ExternalToolDescriptor> descriptors() {
        return registry.descriptors();
    }
}
