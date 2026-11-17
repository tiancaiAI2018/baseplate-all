package com.example.baseplate.security.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.IdempotencyStore;
import com.example.baseplate.api.port.out.KeyValueStore;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.security.config.SecurityProperties;
import com.example.baseplate.security.jwt.JwtVerifier;
import com.example.baseplate.security.jwt.TokenBlacklist;
import com.example.baseplate.security.security.PermissionAspect;
import com.example.baseplate.security.web.CurrentUserProviderImpl;
import com.example.baseplate.security.web.JwtAuthenticationFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 入站安全链装配。依赖已存在的 KeyValueStore / RateLimiter / IdempotencyStore 端口，
 * 这些端口由 adapters 提供（Redis 或内存降级），本模块不直接依赖任何存储实现。
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "com.example.baseplate.adapter.autoconfigure.MemoryPortsAutoConfiguration",
        "com.example.baseplate.adapter.autoconfigure.RedisPortsAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({KeyValueStore.class, RateLimiter.class, IdempotencyStore.class, OneTimeTokenStore.class})
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtVerifier jwtVerifier(SecurityProperties properties) {
        return new JwtVerifier(properties.getJwtSecret());
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenBlacklist tokenBlacklist(KeyValueStore store) {
        return new TokenBlacklist(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtVerifier verifier, TokenBlacklist blacklist) {
        return new JwtAuthenticationFilter(verifier, blacklist);
    }

    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    public CurrentUserProvider currentUserProvider() {
        return new CurrentUserProviderImpl();
    }

    /**
     * 占位 UserDetailsService：阻止 Boot 生成随机密码的默认用户。
     * 本基盘的认证走自研 JWT 过滤链，不使用 DaoAuthenticationProvider。
     */
    @Bean
    @ConditionalOnMissingBean(org.springframework.security.core.userdetails.UserDetailsService.class)
    public org.springframework.security.core.userdetails.UserDetailsService baseplateNoopUserDetailsService() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionAspect permissionAspect(CurrentUserProvider currentUserProvider) {
        return new PermissionAspect(currentUserProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.security.web.WebMvcConfig baseplateWebMvcConfig(
            RateLimiter rateLimiter, IdempotencyStore idempotencyStore,
            OneTimeTokenStore oneTimeTokenStore) {
        return new com.example.baseplate.security.web.WebMvcConfig(
                rateLimiter, idempotencyStore, oneTimeTokenStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<
            com.example.baseplate.security.web.CachedResponseFilter> cachedResponseFilter() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new com.example.baseplate.security.web.CachedResponseFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(5);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.security.web.ApiExceptionHandler apiExceptionHandler() {
        return new com.example.baseplate.security.web.ApiExceptionHandler();
    }
}
