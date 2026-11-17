package com.example.baseplate.identity.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.DataScopeProvider;
import com.example.baseplate.api.port.out.ModuleRegistry;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;
import com.example.baseplate.identity.adapter.out.persistence.mapper.UserMapper;
import com.example.baseplate.identity.application.AdminSeeder;
import com.example.baseplate.identity.application.AuthenticationService;
import com.example.baseplate.identity.application.DefaultDataScopeProvider;
import com.example.baseplate.identity.application.JwtIssuer;
import com.example.baseplate.identity.application.OrgService;
import com.example.baseplate.identity.application.PasswordService;
import com.example.baseplate.identity.application.RoleService;
import com.example.baseplate.identity.application.UserAdminService;
import com.example.baseplate.identity.config.IdentityProperties;
import com.example.baseplate.identity.domain.PasswordHasher;
import com.example.baseplate.identity.domain.PasswordPolicy;
import com.example.baseplate.security.autoconfigure.SecurityAutoConfiguration;
import com.example.baseplate.security.jwt.TokenBlacklist;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;

/**
 * identity 可选能力：有 DataSource、有安全链（令牌吊销）才装配。
 * 不引这个 starter 的项目既没有 sys_user 相关 Bean，也不会有建表脚本注册。
 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnClass({BCryptPasswordEncoder.class, DataSource.class, JdbcTemplate.class})
@ConditionalOnBean({DataSource.class, TokenBlacklist.class, CurrentUserProvider.class})
@MapperScan("com.example.baseplate.identity.adapter.out.persistence.mapper")
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    @ConditionalOnMissingBean
    public PasswordPolicy passwordPolicy(IdentityProperties properties) {
        return new PasswordPolicy(properties.getPasswordMinLength());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtIssuer jwtIssuer(IdentityProperties properties) {
        return new JwtIssuer(properties.getJwtSecret(), properties.getTokenTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityDao identityDao(JdbcTemplate jdbcTemplate) {
        return new IdentityDao(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataScopeProvider dataScopeProvider(IdentityDao dao) {
        return new DefaultDataScopeProvider(dao);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthenticationService authenticationService(IdentityDao dao, PasswordHasher hasher,
                                                       JwtIssuer jwt, IdentityProperties properties,
                                                       com.example.baseplate.security.jwt.TokenBlacklist blacklist) {
        return new AuthenticationService(dao, hasher, jwt, properties, blacklist);
    }

    @Bean
    @ConditionalOnMissingBean
    public PasswordService passwordService(IdentityDao dao, PasswordHasher hasher,
                                           PasswordPolicy policy, IdentityProperties properties,
                                           TokenBlacklist blacklist) {
        return new PasswordService(dao, hasher, policy, properties, blacklist);
    }

    @Bean
    @ConditionalOnMissingBean
    public UserAdminService userAdminService(IdentityDao dao, CurrentUserProvider currentUser,
                                             DataScopeProvider dataScopeProvider, PasswordHasher hasher,
                                             PasswordPolicy policy, IdentityProperties properties,
                                             TokenBlacklist blacklist) {
        return new UserAdminService(dao, currentUser, dataScopeProvider, hasher, policy,
                properties, blacklist);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoleService roleService(IdentityDao dao, CurrentUserProvider currentUser) {
        return new RoleService(dao, currentUser);
    }

    @Bean
    @ConditionalOnMissingBean
    public OrgService orgService(IdentityDao dao, CurrentUserProvider currentUser) {
        return new OrgService(dao, currentUser);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminSeeder adminSeeder(IdentityDao dao, PasswordHasher hasher,
                                   IdentityProperties properties, ModuleRegistry moduleRegistry) {
        return new AdminSeeder(dao, hasher, properties, moduleRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.identity.adapter.out.persistence.UserRepository userRepository(UserMapper mapper) {
        return new com.example.baseplate.identity.adapter.out.persistence.UserRepository(mapper);
    }

    /**
     * 开放平台「代表用户调用」时按用户 ID 取权限快照，和登录态同一份数据。
     * 由 platform 模块按需注入（ObjectProvider），不引开放平台的项目拿到也不影响。
     */
    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.api.port.out.UserDirectory userDirectory(IdentityDao dao) {
        return new com.example.baseplate.identity.application.IdentityUserDirectory(dao);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.identity.adapter.out.persistence.PermissionCatalogRepository
            permissionCatalogRepository(JdbcTemplate jdbcTemplate) {
        return new com.example.baseplate.identity.adapter.out.persistence.PermissionCatalogRepository(jdbcTemplate);
    }

    /**
     * 启动期把各模块清单里的权限点同步进 sys_permission。
     * 跑在 Web 服务就绪前，页面第一次打开就能看到完整目录；重复启动是幂等的。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner permissionCatalogSyncRunner(
            ModuleRegistry moduleRegistry,
            com.example.baseplate.identity.adapter.out.persistence.PermissionCatalogRepository catalog) {
        return args -> {
            catalog.sync(moduleRegistry.loaded());
            org.slf4j.LoggerFactory.getLogger(IdentityAutoConfiguration.class)
                    .info("权限目录已同步：{} 个模块，{} 个权限点",
                            moduleRegistry.loaded().size(), catalog.count());
        };
    }

}
