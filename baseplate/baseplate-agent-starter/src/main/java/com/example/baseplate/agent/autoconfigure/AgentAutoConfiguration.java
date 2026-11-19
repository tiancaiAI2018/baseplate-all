package com.example.baseplate.agent.autoconfigure;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentExternalCallRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentHitlRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentMessageRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentRunRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentSessionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentSkillIndexRepository;
import com.example.baseplate.agent.application.AgentChatService;
import com.example.baseplate.agent.application.AgentConfigService;
import com.example.baseplate.agent.application.AgentSessionService;
import com.example.baseplate.agent.application.SkillIndexService;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.model.ModelProviderFactory;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.agent.tool.PlatformToolProvider;
import com.example.baseplate.agent.tool.ToolRegistry;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.security.autoconfigure.SecurityAutoConfiguration;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体模块装配。
 *
 * <p>依赖 DataSource 与当前用户端口：没有安全模块时不装配，避免半套能力上线。
 * 工具由各模块通过 {@link AgentToolProvider} 贡献，本模块只提供平台只读工具。</p>
 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnBean({DataSource.class, CurrentUserProvider.class})
@ConditionalOnClass({JdbcTemplate.class, ReActAgent.class})
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentDefinitionRepository agentDefinitionRepository(JdbcTemplate jdbcTemplate) {
        return new AgentDefinitionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentSessionRepository agentSessionRepository(JdbcTemplate jdbcTemplate) {
        return new AgentSessionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentMessageRepository agentMessageRepository(JdbcTemplate jdbcTemplate) {
        return new AgentMessageRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRunRepository agentRunRepository(JdbcTemplate jdbcTemplate) {
        return new AgentRunRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentHitlRepository agentHitlRepository(JdbcTemplate jdbcTemplate) {
        return new AgentHitlRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentExternalCallRepository agentExternalCallRepository(JdbcTemplate jdbcTemplate) {
        return new AgentExternalCallRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentSkillIndexRepository agentSkillIndexRepository(JdbcTemplate jdbcTemplate) {
        return new AgentSkillIndexRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillIndexService agentSkillIndexService(AgentSkillIndexRepository repository,
                                                    AgentProperties properties) {
        return new SkillIndexService(repository, properties);
    }

    /**
     * 会话上下文存储到 PostgreSQL。createIfNotExist=true 是为了让未跑 Flyway 的本地环境
     * 也能启动；迁移文件里同样建了这张表，两边结构保持一致。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentStateStore agentStateStore(DataSource dataSource, AgentProperties properties) {
        return new PostgresAgentStateStore(dataSource, properties.getStateSchema(),
                properties.getStateTable(), true);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry agentToolRegistry(ObjectProvider<AgentToolProvider> providers) {
        List<AgentToolProvider> all = new ArrayList<>();
        providers.orderedStream().forEach(all::add);
        // 平台只读工具是模块自带能力，不依赖其它 starter
        all.add(new PlatformToolProvider());
        return new ToolRegistry(all);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelProviderFactory agentModelProviderFactory(AgentProperties properties) {
        return new ModelProviderFactory(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntimeFactory agentRuntimeFactory(ToolRegistry toolRegistry,
                                                   ModelProviderFactory modelProviderFactory,
                                                   AgentProperties properties,
                                                   AgentStateStore agentStateStore,
                                                   SkillIndexService skillIndexService,
                                                   AgentDefinitionRepository definitionRepository) {
        return new AgentRuntimeFactory(toolRegistry, modelProviderFactory, properties,
                agentStateStore, skillIndexService, definitionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentConfigService agentConfigService(AgentDefinitionRepository repository,
                                                 ToolRegistry toolRegistry,
                                                 AgentRuntimeFactory runtimeFactory,
                                                 AgentProperties properties,
                                                 SkillIndexService skillIndexService) {
        return new AgentConfigService(repository, toolRegistry, runtimeFactory, properties,
                skillIndexService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentSessionService agentSessionService(AgentSessionRepository sessionRepository,
                                                   AgentMessageRepository messageRepository) {
        return new AgentSessionService(sessionRepository, messageRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentChatService agentChatService(AgentDefinitionRepository definitionRepository,
                                             AgentSessionRepository sessionRepository,
                                             AgentMessageRepository messageRepository,
                                             AgentRunRepository runRepository,
                                             AgentHitlRepository hitlRepository,
                                             AgentExternalCallRepository externalCallRepository,
                                             AgentRuntimeFactory runtimeFactory,
                                             ToolRegistry toolRegistry,
                                             AgentProperties properties) {
        return new AgentChatService(definitionRepository, sessionRepository, messageRepository,
                runRepository, hitlRepository, externalCallRepository, runtimeFactory,
                toolRegistry, properties);
    }

    /**
     * 启动期：同步只读工具目录，并在默认租户没有 Agent 时用 .env 建一个默认助手。
     * 放在 SmartInitializingSingleton 里，确保 Flyway 迁移已经执行完。
     */
    @Bean
    public SmartInitializingSingleton agentCatalogSeeder(AgentDefinitionRepository repository,
                                                         ToolRegistry toolRegistry,
                                                         AgentConfigService configService,
                                                         SkillIndexService skillIndexService) {
        return () -> {
            repository.syncToolCatalog(toolRegistry.descriptors());
            // 技能内容在本地目录，启动时同步一次索引；改文件后可在页面点「重新扫描」
            skillIndexService.scan();
            configService.seedDefaultIfAbsent();
        };
    }
}
