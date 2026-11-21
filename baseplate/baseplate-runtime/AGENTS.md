# BASEPLATE RUNTIME

## OVERVIEW
运行时基础设施：端口注册表（含歧义检测）、配置项 AES-GCM 解密、租户上下文。

## STRUCTURE
```
com.example.baseplate.runtime/
├── port/PortRegistry.java、AmbiguousPortBindingException.java
├── crypto/AesGcmCipher.java、CiphertextTool.java、
│         DecryptingPropertySource.java、EncryptedPropertyEnvironmentPostProcessor.java
├── tenant/TenantContext.java
├── config/BaseplateProperties.java
└── autoconfigure/RuntimeAutoConfiguration.java
```

## CONVENTIONS
- 一个端口类型只允许一个主绑定；多个候选必须显式指定，否则 `AmbiguousPortBindingException`。
- 加密配置项用 `ENC(...)` 包裹，由 `EncryptedPropertyEnvironmentPostProcessor` 在环境装配早期解密。
- `TenantContext` 只作请求作用域传递；业务查询仍以 `CurrentUser.tenantId()` 为准。
- `BASEPLATE_SECRET_KEY` 用于解密库中应用密钥，与 JWT/开放令牌密钥分开。

## ANTI-PATTERNS
- 业务代码直接 new 适配器绕过注册表。
- 在静态字段缓存租户（线程复用会串租户）。
- 把解密后的密钥写入日志。
