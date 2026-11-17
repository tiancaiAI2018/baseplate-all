package com.example.baseplate.registry.autoconfigure;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.registry.LoadedModule;
import com.example.baseplate.registry.MigrationPlanWriter;
import com.example.baseplate.registry.ModuleDescriptorLoader;
import com.example.baseplate.registry.ModuleDescriptorValidator;
import com.example.baseplate.registry.ModuleRegistryImpl;
import com.example.baseplate.registry.StartupVerifier;
import com.example.baseplate.runtime.port.PortRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 清单驱动装配：发现 → 校验 → 注册。新模块不改这里。 */
@AutoConfiguration
public class ModuleRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModuleDescriptorLoader moduleDescriptorLoader() {
        return new ModuleDescriptorLoader();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModuleDescriptorValidator moduleDescriptorValidator() {
        return new ModuleDescriptorValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public MigrationPlanWriter migrationPlanWriter() {
        return new MigrationPlanWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    public StartupVerifier startupVerifier(ModuleDescriptorValidator validator) {
        return new StartupVerifier(validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModuleRegistry moduleRegistry(ModuleDescriptorLoader loader,
                                         StartupVerifier verifier,
                                         PortRegistry ports) {
        List<LoadedModule> modules = loader.load();
        verifier.verify(modules, ports);
        Set<String> enabled = modules.stream()
                .filter(LoadedModule::enabled)
                .map(module -> module.descriptor().id())
                .collect(Collectors.toUnmodifiableSet());
        return new ModuleRegistryImpl(modules, enabled, ports);
    }
}
