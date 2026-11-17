package com.example.baseplate.bootstrap.startup;

import com.example.baseplate.adapter.out.persistence.mybatis.ModuleRegistryRepository;
import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.port.out.ModuleRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动期把清单落到 bp_module_registry：事后可回答「这次跑的是哪些模块」。 */
@Component
public class ModuleRegistryPersistenceRunner implements ApplicationRunner {

    private final ModuleRegistry moduleRegistry;
    private final ModuleRegistryRepository repository;

    public ModuleRegistryPersistenceRunner(ModuleRegistry moduleRegistry,
                                           ModuleRegistryRepository repository) {
        this.moduleRegistry = moduleRegistry;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (ModuleDescriptor descriptor : moduleRegistry.loaded()) {
            repository.upsert(descriptor.id(), descriptor.version(),
                    moduleRegistry.enabled(descriptor.id()), null);
        }
    }
}
