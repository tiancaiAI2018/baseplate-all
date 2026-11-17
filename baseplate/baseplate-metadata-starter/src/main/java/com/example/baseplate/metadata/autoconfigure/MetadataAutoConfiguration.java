package com.example.baseplate.metadata.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.metadata.application.MetadataService;
import com.example.baseplate.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 低代码元数据可选能力：有 DataSource 且已启用安全链才装配。 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnBean({DataSource.class, CurrentUserProvider.class})
@ConditionalOnClass(JdbcTemplate.class)
public class MetadataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetadataService metadataService(JdbcTemplate jdbcTemplate,
                                           CurrentUserProvider currentUserProvider) {
        return new MetadataService(jdbcTemplate, currentUserProvider);
    }


}
