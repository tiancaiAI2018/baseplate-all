package com.example.baseplate.runtime.port;

import com.example.baseplate.api.model.Capability;
import com.example.baseplate.api.model.PortBinding;
import com.example.baseplate.api.port.out.ModuleRegistry;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 端口注册表：启动期扫描 api.port.out 下的接口，找出容器里的唯一实现。
 * 每端口恰好一个 Bean；0 个视为该能力未装配，多于 1 个直接启动失败。
 */
public final class PortRegistry {

    private static final String PORT_PACKAGE = "com.example.baseplate.api.port.out";

    private final List<PortBinding> bindings;
    private final Map<String, String> implByPort;

    private PortRegistry(List<PortBinding> bindings, Map<String, String> implByPort) {
        this.bindings = List.copyOf(bindings);
        this.implByPort = Map.copyOf(implByPort);
    }

    public static PortRegistry scan(ApplicationContext context) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        List<PortBinding> bindings = new ArrayList<>();
        Map<String, String> implByPort = new LinkedHashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PORT_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> portType = ClassUtils.resolveClassName(className, PortRegistry.class.getClassLoader());
            // 注册表自身不作为端口登记，否则 getBean 会触发 ModuleRegistry → PortRegistry 循环依赖
            if (portType == ModuleRegistry.class) {
                continue;
            }
            String[] beanNames = context.getBeanNamesForType(portType);
            if (beanNames.length == 0) {
                continue;
            }
            if (beanNames.length > 1) {
                throw new AmbiguousPortBindingException(portType.getName(), beanNames);
            }
            Object bean = context.getBean(beanNames[0]);
            bindings.add(new PortBinding(portType.getSimpleName(), bean.getClass().getName(),
                    "auto", Set.<Capability>of()));
            implByPort.put(portType.getSimpleName(), bean.getClass().getName());
        }
        return new PortRegistry(bindings, implByPort);
    }

    public List<PortBinding> bindings() {
        return bindings;
    }

    public Optional<String> implementationOf(String portName) {
        return Optional.ofNullable(implByPort.get(portName));
    }
}
