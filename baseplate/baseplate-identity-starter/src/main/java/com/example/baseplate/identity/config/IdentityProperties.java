package com.example.baseplate.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** identity 配置：JWT 密钥只允许来自环境变量，默认值仅供本地启动。 */
@ConfigurationProperties(prefix = "baseplate.identity")
public class IdentityProperties {

    private String jwtSecret = System.getenv().getOrDefault("BASEPLATE_JWT_SECRET", "local-dev-only-change-me");
    private Duration tokenTtl = Duration.ofHours(2);
    private int passwordMinLength = 8;
    private int passwordHistorySize = 5;
    private boolean passwordExpireEnabled = false;
    private int passwordExpireDays = 90;
    private int maxFailedAttempts = 5;
    private int lockMinutes = 15;
    private String defaultTenant = "default";
    private String adminUsername = "admin";
    private String adminPassword = System.getenv().getOrDefault("BASEPLATE_ADMIN_PASSWORD", "Admin@12345");
    private boolean adminSeedEnabled = true;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public int getPasswordMinLength() {
        return passwordMinLength;
    }

    public void setPasswordMinLength(int passwordMinLength) {
        this.passwordMinLength = passwordMinLength;
    }

    public int getPasswordHistorySize() {
        return passwordHistorySize;
    }

    public void setPasswordHistorySize(int passwordHistorySize) {
        this.passwordHistorySize = passwordHistorySize;
    }

    public boolean isPasswordExpireEnabled() {
        return passwordExpireEnabled;
    }

    public void setPasswordExpireEnabled(boolean passwordExpireEnabled) {
        this.passwordExpireEnabled = passwordExpireEnabled;
    }

    public int getPasswordExpireDays() {
        return passwordExpireDays;
    }

    public void setPasswordExpireDays(int passwordExpireDays) {
        this.passwordExpireDays = passwordExpireDays;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public int getLockMinutes() {
        return lockMinutes;
    }

    public void setLockMinutes(int lockMinutes) {
        this.lockMinutes = lockMinutes;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public boolean isAdminSeedEnabled() {
        return adminSeedEnabled;
    }

    public void setAdminSeedEnabled(boolean adminSeedEnabled) {
        this.adminSeedEnabled = adminSeedEnabled;
    }
}
