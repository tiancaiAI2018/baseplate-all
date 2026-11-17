package com.example.baseplate.bootstrap.actuator;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.PortBinding;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.runtime.port.PortRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /actuator/ports：查看每个端口当前绑定的实现与模块清单。
 * 验证「换实现」时看这里，比翻日志快。
 */
@Component
@Endpoint(id = "ports")
public class PortsEndpoint {

    private final PortRegistry portRegistry;
    private final ModuleRegistry moduleRegistry;

    public PortsEndpoint(PortRegistry portRegistry, ModuleRegistry moduleRegistry) {
        this.portRegistry = portRegistry;
        this.moduleRegistry = moduleRegistry;
    }

    @ReadOperation
    public Map<String, Object> ports() {
        List<PortBinding> bindings = portRegistry.bindings();
        List<ModuleDescriptor> modules = moduleRegistry.loaded();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("portCount", bindings.size());
        result.put("ports", bindings);
        result.put("modules", modules.stream().map(module -> Map.of(
                "id", module.id(), "version", module.version(),
                "enabled", moduleRegistry.enabled(module.id()))).toList());
        return result;
    }
}
