package com.example.baseplate.cend.autoconfigure;

import com.example.baseplate.agent.autoconfigure.AgentAutoConfiguration;
import com.example.baseplate.cend.application.CendAgentToolProvider;
import com.example.baseplate.cend.application.CendAgentTools;
import com.example.baseplate.metadata.application.MetadataService;
import com.example.baseplate.metadata.autoconfigure.MetadataAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {AgentAutoConfiguration.class, MetadataAutoConfiguration.class})
public class CendAutoConfiguration {

    @Bean
    public CendAgentTools cendAgentTools(MetadataService metadataService) {
        return new CendAgentTools(metadataService);
    }

    @Bean
    public CendAgentToolProvider cendAgentToolProvider(CendAgentTools tools) {
        return new CendAgentToolProvider(tools);
    }
}
