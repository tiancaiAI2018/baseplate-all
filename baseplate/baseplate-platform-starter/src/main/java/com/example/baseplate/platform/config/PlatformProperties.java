package com.example.baseplate.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 开放平台配置：两把密钥（token 签名 / 请求签名）语义不同，禁止复用同一值。 */
@ConfigurationProperties(prefix = "baseplate.platform")
public class PlatformProperties {

    /**
     * 开放平台令牌签名密钥，必须与内部登录 JWT 的密钥不同。
     * 这里曾回兜 BASEPLATE_JWT_SECRET，结果第三方取到的 access_token 能通过内部
     * JwtAuthenticationFilter 的校验（只是 username 为空），等于越权进了 /api/**。
     */
    private String tokenSecret = System.getenv().getOrDefault("BASEPLATE_OPEN_TOKEN_SECRET",
            "local-dev-open-token-secret");
    private String secretKey = System.getenv().getOrDefault("BASEPLATE_SECRET_KEY",
            "local-dev-only-change-me-secret-key");
    private Duration tokenTtl = Duration.ofHours(2);
    private Duration timestampWindow = Duration.ofMinutes(5);
    /** 授权码有效期：只够第三方后端立刻来换一次，不给人留重放窗口。 */
    private Duration authCodeTtl = Duration.ofMinutes(5);
    /** 刷新令牌有效期；过期后必须让用户重新授权一次。 */
    private Duration refreshTtl = Duration.ofDays(30);
    private boolean sandboxEnabled = true;
    private String sandboxTenantPrefix = "sandbox:";

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getTimestampWindow() {
        return timestampWindow;
    }

    public void setTimestampWindow(Duration timestampWindow) {
        this.timestampWindow = timestampWindow;
    }

    public Duration getAuthCodeTtl() {
        return authCodeTtl;
    }

    public void setAuthCodeTtl(Duration authCodeTtl) {
        this.authCodeTtl = authCodeTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    public void setSandboxEnabled(boolean sandboxEnabled) {
        this.sandboxEnabled = sandboxEnabled;
    }

    public String getSandboxTenantPrefix() {
        return sandboxTenantPrefix;
    }

    public void setSandboxTenantPrefix(String sandboxTenantPrefix) {
        this.sandboxTenantPrefix = sandboxTenantPrefix;
    }
}
