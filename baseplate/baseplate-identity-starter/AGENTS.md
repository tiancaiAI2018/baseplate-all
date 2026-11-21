# IDENTITY STARTER

## OVERVIEW
身份与权限：用户、角色、组织、权限目录、登录认证、JWT 签发、密码策略与账号锁定。所有业务表的租户上下文最终来自这里的 `CurrentUser`。

## STRUCTURE
```
com.example.baseplate.identity/
├── adapter/in/web/        # Auth / UserAdmin / Role / Org / Permission Controller
├── adapter/out/persistence/ # IdentityDao、UserRepository、PermissionCatalogRepository、UserDO/Mapper
├── application/           # AuthenticationService、JwtIssuer、PasswordService、UserAdminService、
│                          # RoleService、OrgService、DefaultDataScopeProvider、IdentityUserDirectory、AdminSeeder
├── domain/                # PasswordHasher、PasswordPolicy
├── config/                # IdentityProperties
└── autoconfigure/IdentityAutoConfiguration.java
resources/db/migration/identity/  # admin、权限目录
resources/META-INF/baseplate-module.yml
```

## CONVENTIONS
- 密码用 `PasswordHasher`，不自己实现哈希；策略来自 `baseplate.identity.password-*`。
- 账号锁定看 `failed_attempts` / `locked_until`，不是 `status`；重置密码要顺带解锁。
- 首登改密由 `MustChangePasswordFilter` 拦截，返回 `PASSWORD_CHANGE_REQUIRED`。
- 权限码 `user:* role:* org:*` 与注解逐字符一致。
- 数据权限维度：`User -> org_id`。

## ANTI-PATTERNS
- 用用户名当办理人 ID（与 `userId` 混用）。
- 登录成功后不清用户级吊销标记（同一秒重置+登录会误杀新令牌）。
- 回归脚本重置真实演示账号（会把用户锁在门外）。
