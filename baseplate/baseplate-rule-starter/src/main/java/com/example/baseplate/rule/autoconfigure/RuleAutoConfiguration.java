package com.example.baseplate.rule.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.api.port.out.RuleEvaluator;
import com.example.baseplate.rule.adapter.out.memory.InMemoryRuleSetRepository;
import com.example.baseplate.rule.adapter.out.persistence.DbRuleSetRepository;
import com.example.baseplate.rule.application.RuleFieldRegistry;
import com.example.baseplate.rule.application.StructuredRuleInterpreter;
import com.example.baseplate.rule.domain.RuleSetRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * rule 能力：有 DataSource 且能取到当前用户时落库，否则内存实现。
 * 只暴露一个 RuleSetRepository Bean，避免容器里出现两个候选。
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.example.baseplate.registry.autoconfigure.ModuleRegistryAutoConfiguration")
public class RuleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RuleSetRepository ruleSetRepository(ObjectProvider<JdbcTemplate> jdbcTemplate,
                                               ObjectProvider<CurrentUserProvider> currentUserProvider) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        CurrentUserProvider currentUser = currentUserProvider.getIfAvailable();
        if (jdbc != null && currentUser != null) {
            return new DbRuleSetRepository(jdbc, currentUser);
        }
        return new InMemoryRuleSetRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleEvaluator ruleEvaluator(RuleSetRepository repository) {
        return new StructuredRuleInterpreter(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleFieldRegistry ruleFieldRegistry(ModuleRegistry moduleRegistry) {
        return new RuleFieldRegistry(moduleRegistry);
    }

}
