package com.example.baseplate.dataagent.autoconfigure;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.autoconfigure.AgentAutoConfiguration;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.dataagent.adapter.out.persistence.DataMetadataRepository;
import com.example.baseplate.dataagent.tool.DataAgentTools;
import com.example.baseplate.dataagent.tool.DataAgentToolProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AgentAutoConfiguration.class)
@ConditionalOnClass(AgentToolProvider.class)
public class DataAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "dataAgentToolProvider")
    public AgentToolProvider dataAgentToolProvider(DataMetadataRepository repository) {
        return new DataAgentToolProvider(new DataAgentTools(repository));
    }

    @Bean
    @ConditionalOnMissingBean
    public DataAgentSeeder dataAgentSeeder(AgentDefinitionRepository repository,
                                           AgentProperties properties,
                                           AgentRuntimeFactory runtimeFactory,
                                           @Qualifier("dataAgentToolProvider") AgentToolProvider provider) {
        return new DataAgentSeeder(repository, properties, runtimeFactory, provider);
    }
}
