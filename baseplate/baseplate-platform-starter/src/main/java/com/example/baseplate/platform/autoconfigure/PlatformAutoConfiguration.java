package com.example.baseplate.platform.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ExternalServiceGateway;
import com.example.baseplate.api.port.out.ExternalToolDescriptorProvider;
import com.example.baseplate.api.port.out.NonceStore;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.api.port.out.UserDirectory;
import com.example.baseplate.platform.adapter.in.web.OpenApiAnnotationScanner;
import com.example.baseplate.platform.adapter.in.web.OpenApiAuthFilter;
import com.example.baseplate.platform.adapter.out.persistence.OpenApiCatalogRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenAppRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenAuthCodeRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenRefreshTokenRepository;
import com.example.baseplate.platform.application.ExternalServiceGatewayImpl;
import com.example.baseplate.platform.application.ExternalServiceRegistry;
import com.example.baseplate.platform.application.OpenAppService;
import com.example.baseplate.platform.application.PlatformExternalToolProvider;
import com.example.baseplate.platform.config.PlatformProperties;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.token.OpenTokenService;
import com.example.baseplate.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/** 开放平台可选能力：有 DataSource 与 nonce/限流端口才装配。 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnBean({DataSource.class, NonceStore.class, RateLimiter.class, CurrentUserProvider.class})
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecretCipher secretCipher(PlatformProperties properties) {
        return new SecretCipher(properties.getSecretKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalServiceRegistry externalServiceRegistry(SecretCipher cipher) {
        return new ExternalServiceRegistry(cipher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalServiceGateway externalServiceGateway(ExternalServiceRegistry registry,
                                                         SecretCipher cipher) {
        return new ExternalServiceGatewayImpl(registry, cipher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalToolDescriptorProvider externalToolDescriptorProvider(
            ExternalServiceRegistry registry) {
        return new PlatformExternalToolProvider(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAppRepository openAppRepository(JdbcTemplate jdbcTemplate) {
        return new OpenAppRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenApiCatalogRepository openApiCatalogRepository(JdbcTemplate jdbcTemplate) {
        return new OpenApiCatalogRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAuthCodeRepository openAuthCodeRepository(JdbcTemplate jdbcTemplate) {
        return new OpenAuthCodeRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenRefreshTokenRepository openRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        return new OpenRefreshTokenRepository(jdbcTemplate);
    }

    /**
     * 启动期扫描 {@code @OpenApi} 并同步目录。
     *
     * <p>必须等到所有单例就绪（{@link SmartInitializingSingleton}）才能扫：那时
     * {@link RequestMappingHandlerMapping} 才把「类前缀 + 方法路径」拼完，
     * 而 Web 容器此刻还没开始接收请求，目录不会出现空窗期。</p>
     */
    @Bean
    public SmartInitializingSingleton openApiCatalogSync(
            OpenApiCatalogRepository catalogRepository,
            OpenAuthCodeRepository authCodeRepository,
            OpenRefreshTokenRepository refreshTokenRepository,
            ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return () -> {
            List<RequestMappingHandlerMapping> available = new ArrayList<>();
            mappings.orderedStream().forEach(available::add);
            catalogRepository.ensureCatalog(new OpenApiAnnotationScanner(available).scan());
            // 顺手清掉过期的一次性凭证，量很小，不需要定时任务
            authCodeRepository.purge();
            refreshTokenRepository.purge();
        };
    }

    /**
     * 启动期自检：开放平台令牌密钥与登录 JWT 密钥相同，第三方 access_token 就能被内部
     * 鉴权链当成合法用户（只是没有用户名），属于越权。宁可起不来，也不要带病上线。
     */
    @Bean
    public SmartInitializingSingleton openTokenSecretGuard(
            PlatformProperties platform, com.example.baseplate.security.config.SecurityProperties security) {
        return () -> {
            String open = platform.getTokenSecret();
            String jwt = security.getJwtSecret();
            if (open != null && !open.isBlank() && open.equals(jwt)) {
                throw new IllegalStateException(
                        "开放平台令牌密钥不能与登录 JWT 密钥相同：请为 BASEPLATE_OPEN_TOKEN_SECRET 设置独立值");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenTokenService openTokenService(PlatformProperties properties) {
        return new OpenTokenService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAppService openAppService(OpenAppRepository repository,
                                         OpenApiCatalogRepository catalogRepository,
                                         OpenRefreshTokenRepository refreshTokenRepository,
                                         SecretCipher cipher,
                                         CurrentUserProvider currentUser, PlatformProperties properties) {
        return new OpenAppService(repository, catalogRepository, refreshTokenRepository,
                cipher, currentUser, properties);
    }

    /**
     * 用户目录由 identity 模块提供；只装 platform 不装 identity 时为空，
     * 此时 USER 类型接口会明确返回 503 USER_DIRECTORY_UNAVAILABLE，而不是放行。
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<OpenApiAuthFilter> openApiAuthFilter(
            OpenAppRepository repository, OpenApiCatalogRepository catalogRepository,
            SecretCipher cipher, OpenTokenService tokenService,
            NonceStore nonceStore, RateLimiter rateLimiter, PlatformProperties properties,
            ObjectProvider<UserDirectory> userDirectory) {
        FilterRegistrationBean<OpenApiAuthFilter> registration = new FilterRegistrationBean<>(
                new OpenApiAuthFilter(repository, catalogRepository, cipher, tokenService,
                        nonceStore, rateLimiter, properties, userDirectory.getIfAvailable()));
        registration.addUrlPatterns("/openapi/*");
        registration.setOrder(-90);
        return registration;
    }
}
