package com.example.baseplate.registry;

import com.example.baseplate.api.model.PortBinding;
import com.example.baseplate.runtime.port.PortRegistry;

import java.util.ArrayList;
import java.util.List;

/** 启动自检：清单校验 + 端口唯一性 + 空实现告警，失败即阻止启动。 */
public final class StartupVerifier {

    private final ModuleDescriptorValidator validator;

    public StartupVerifier(ModuleDescriptorValidator validator) {
        this.validator = validator;
    }

    public void verify(List<LoadedModule> modules, PortRegistry ports) {
        validator.validate(modules);

        List<String> problems = new ArrayList<>();
        for (PortBinding binding : ports.bindings()) {
            if (binding.implClass() == null || binding.implClass().isBlank()) {
                problems.add("端口 " + binding.portName() + " 的实现类为空");
            }
        }
        if (!problems.isEmpty()) {
            throw new ModuleLoadException("启动自检失败:\n  - " + String.join("\n  - ", problems));
        }
    }
}
