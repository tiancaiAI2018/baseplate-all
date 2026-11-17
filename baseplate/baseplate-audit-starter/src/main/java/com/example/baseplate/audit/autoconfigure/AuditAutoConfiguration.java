package com.example.baseplate.audit.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.audit.adapter.in.web.AccessLogFilter;
import com.example.baseplate.audit.application.AuditAspect;
import com.example.baseplate.audit.application.AuditWriter;
import com.example.baseplate.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 审计可选能力：有 DataSource 才装配。 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnBean({DataSource.class, CurrentUserProvider.class})
@ConditionalOnClass(JdbcTemplate.class)
public class AuditAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public AuditWriter auditWriter(JdbcTemplate jdbcTemplate) {
        return new AuditWriter(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditAspect auditAspect(AuditWriter writer, CurrentUserProvider currentUserProvider) {
        return new AuditAspect(writer, currentUserProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter(AuditWriter writer) {
        FilterRegistrationBean<AccessLogFilter> registration =
                new FilterRegistrationBean<>(new AccessLogFilter(writer));
        registration.setOrder(0);
        registration.addUrlPatterns("/api/*", "/openapi/*");
        return registration;
    }


}
