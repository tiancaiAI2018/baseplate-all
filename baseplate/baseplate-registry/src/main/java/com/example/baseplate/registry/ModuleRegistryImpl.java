package com.example.baseplate.registry;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.PortBinding;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.runtime.port.PortRegistry;

import java.util.List;
import java.util.Set;

/** 模块注册表实现：只读查询，运行期不改变 enabled 状态。 */
public final class ModuleRegistryImpl implements ModuleRegistry {

    private final List<LoadedModule> modules;
    private final Set<String> enabled;
    private final PortRegistry ports;

    public ModuleRegistryImpl(List<LoadedModule> modules, Set<String> enabled, PortRegistry ports) {
        this.modules = List.copyOf(modules);
        this.enabled = Set.copyOf(enabled);
        this.ports = ports;
    }

    @Override
    public List<ModuleDescriptor> loaded() {
        return modules.stream().map(LoadedModule::descriptor).toList();
    }

    @Override
    public boolean enabled(String moduleId) {
        return enabled.contains(moduleId);
    }

    @Override
    public List<PortBinding> bindings() {
        return ports.bindings();
    }
}
