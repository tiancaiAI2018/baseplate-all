package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.PortBinding;

import java.util.List;

/** 只读查询端口：实现类在 registry 模块，业务模块只依赖本接口。 */
public interface ModuleRegistry {
    List<ModuleDescriptor> loaded();

    boolean enabled(String moduleId);

    List<PortBinding> bindings();
}
