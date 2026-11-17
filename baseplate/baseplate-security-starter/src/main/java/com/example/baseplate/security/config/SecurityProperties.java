package com.example.baseplate.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 入站安全配置。JWT 密钥与 identity 模块读同一个环境变量，禁止各自写死不同值。
 */
@ConfigurationProperties(prefix = "baseplate.security")
public class SecurityProperties {

    private String jwtSecret = System.getenv().getOrDefault("BASEPLATE_JWT_SECRET", "local-dev-only-change-me");
    private String internalSignSecret = System.getenv().getOrDefault("BASEPLATE_SIGN_SECRET", "local-dev-sign-secret");
    private Duration clockSkew = Duration.ofSeconds(30);
    private String traceHeader = "X-Trace-Id";
    private List<String> permitPaths = new ArrayList<>(List.of(
            "/api/auth/login",
            "/openapi/**",
            "/actuator/health",
            "/actuator/info",
            "/error",
            "/",
            "/index.html",
            "/assets/**",
            "/favicon.ico",
            "/favicon.svg"));
    private String mustChangeAllowedPaths = "/api/auth/password,/api/auth/logout,/api/auth/me,/actuator/health";

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getInternalSignSecret() {
        return internalSignSecret;
    }

    public void setInternalSignSecret(String internalSignSecret) {
        this.internalSignSecret = internalSignSecret;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public String getTraceHeader() {
        return traceHeader;
    }

    public void setTraceHeader(String traceHeader) {
        this.traceHeader = traceHeader;
    }

    public List<String> getPermitPaths() {
        return permitPaths;
    }

    public void setPermitPaths(List<String> permitPaths) {
        this.permitPaths = permitPaths;
    }

    public String getMustChangeAllowedPaths() {
        return mustChangeAllowedPaths;
    }

    public void setMustChangeAllowedPaths(String mustChangeAllowedPaths) {
        this.mustChangeAllowedPaths = mustChangeAllowedPaths;
    }
}
