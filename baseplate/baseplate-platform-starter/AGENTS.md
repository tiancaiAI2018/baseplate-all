# PLATFORM STARTER (开放平台)

## OVERVIEW
把内部接口开放给第三方：应用双凭证、`@OpenApi` 注解扫描、开放令牌（访问令牌 + 刷新令牌 + 授权码）、签名、调用日志。

## STRUCTURE
```
com.example.baseplate.platform/
├── adapter/in/web/    # OpenApiController、OpenOAuthAuthorizeController、OpenApiAuthFilter、
│                      # OpenApiAnnotationScanner、PlatformApiCatalogController、PlatformConsoleController
├── adapter/out/persistence/ # OpenApp / OpenAuthCode / OpenRefreshToken / OpenApiCatalog Repository
├── application/OpenAppService.java
├── domain/            # OpenApp、OpenApiDef
├── token/OpenTokenService.java
├── signature/OpenApiSignature.java
├── crypto/SecretCipher.java
├── config/PlatformProperties.java
└── autoconfigure/PlatformAutoConfiguration.java
resources/db/migration/platform/  # platform、api catalog、授权默认接口
resources/META-INF/baseplate-module.yml
```

## RED LINES
1. `BASEPLATE_OPEN_TOKEN_SECRET` 必须与登录 JWT 密钥不同；启动期自检，同值直接失败。
2. `@OpenApi` 只能标在 `/openapi/**` 处理器；标错启动期抛异常。
3. `subject = USER` 只认授权码换来、带 `uid` 的令牌；机器令牌必须 403，不得降级匿名。
4. 只有支持 `authorization_code` 的应用才能被勾 USER 接口；前端置灰不算，后端必须再拦。

## CONVENTIONS
- 扫描路径用 `RequestMappingHandlerMapping.getHandlerMethods()`（类级前缀此时才拼好）。
- `JwtAuthenticationFilter.shouldNotFilter` 必须跳过 `/openapi/`。
- 应用密钥落库前用 `SecretCipher` 加密；只读视图不得返回 `clientSecretEnc`。
- refresh 令牌轮换，旧令牌立即失效。
- 授权目录同步：`label_edited` 保护人工名称，安全字段（method/path/subject/permission）以代码为准。

## ANTI-PATTERNS
- 用同一个密钥签内部 JWT 和开放令牌。
- 「全部授权」把置灰接口也算进去。
- 第三方请求在审计里 tenant 为 null 导致整行消失。
