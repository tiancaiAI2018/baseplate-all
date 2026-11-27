package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.ExternalToolDescriptor;

import java.util.List;

/** 平台把已注册外部服务的端点投影成 Agent 工具描述。 */
public interface ExternalToolDescriptorProvider {

    List<ExternalToolDescriptor> descriptors();
}
