package com.example.baseplate.runtime.autoconfigure;

import com.example.baseplate.api.port.out.Clock;
import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.runtime.config.BaseplateProperties;
import com.example.baseplate.runtime.port.PortRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.UUID;

/** 运行时基础装配：配置属性、端口注册表、时钟与 ID。 */
@AutoConfiguration
@EnableConfigurationProperties(BaseplateProperties.class)
public class RuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PortRegistry portRegistry(ApplicationContext applicationContext) {
        return PortRegistry.scan(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Instant::now;
    }

    @Bean
    @ConditionalOnMissingBean
    public IdGenerator idGenerator() {
        return () -> UUID.randomUUID().toString();
    }
}
