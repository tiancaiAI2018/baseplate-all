package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.Idempotent;
import com.example.baseplate.api.annotation.OpenApi;
import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.api.port.out.UserDirectory;
import com.example.baseplate.platform.adapter.out.persistence.OpenAppRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenAuthCodeRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenRefreshTokenRepository;
import com.example.baseplate.platform.config.PlatformProperties;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.token.OpenTokenService;
import com.example.baseplate.security.web.CurrentUserProviderImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放平台入口：令牌端点在鉴权链之外，其余全部经过 {@link OpenApiAuthFilter}。
 *
 * <p>三个内置接口都通过 {@code @OpenApi} 声明，目录由启动期扫描生成——
 * 硬编码目录已经删掉，新增业务接口不需要再来这里登记。</p>
 */
@RestController
@RequestMapping("/openapi/v1")
public class OpenApiController {

    private final OpenAppRepository repository;
    private final SecretCipher cipher;
    private final OpenTokenService tokenService;
    private final OpenAuthCodeRepository authCodeRepository;
    private final OpenRefreshTokenRepository refreshTokenRepository;
    private final PlatformProperties properties;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<UserDirectory> userDirectories;

    public OpenApiController(OpenAppRepository repository, SecretCipher cipher,
                             OpenTokenService tokenService,
                             OpenAuthCodeRepository authCodeRepository,
                             OpenRefreshTokenRepository refreshTokenRepository,
                             PlatformProperties properties, RateLimiter rateLimiter,
                             ObjectProvider<UserDirectory> userDirectories) {
        this.repository = repository;
        this.cipher = cipher;
        this.tokenService = tokenService;
        this.authCodeRepository = authCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.userDirectories = userDirectories;
    }

    // ───────────────────────────── 令牌端点 ─────────────────────────────

    /**
     * 一个端点支持三种 grant：
     * <ul>
     *   <li>{@code client_credentials}：应用身份，没有 uid；</li>
     *   <li>{@code authorization_code}：用户身份，带 uid，仅在用户点过同意后可用；</li>
     *   <li>{@code refresh_token}：续期，轮换旧令牌。</li>
     * </ul>
     * 字段同时接受 OAuth2 标准的下划线写法与早期驼峰写法，避免接入方踩坑。
     */
    @PostMapping("/oauth/token")
    public Map<String, Object> token(@RequestBody(required = false) Map<String, String> body,
                                     @RequestHeader(value = "Authorization", required = false) String authorization,
                                     HttpServletRequest request) {
        Credentials credentials = credentials(body, authorization);
        // 换令牌是最容易被暴力猜 secret 的入口，先按应用/来源 IP 限流
        String bucket = credentials.appKey() == null ? clientIp(request) : credentials.appKey();
        if (!rateLimiter.tryAcquire("default", "open:token:" + bucket, 60, Duration.ofMinutes(1))) {
            throw new OAuthError(429, "too_many_requests", "换令牌过于频繁，请稍后再试");
        }
        String grantType = field(body, "grantType", "grant_type");
        String grant = grantType == null || grantType.isBlank() ? "client_credentials" : grantType.trim();
        return switch (grant) {
            case "client_credentials" -> clientCredentials(credentials, body);
            case "authorization_code" -> authorizationCode(credentials, body);
            case "refresh_token" -> refreshToken(credentials, body);
            default -> throw new OAuthError(400, "unsupported_grant_type",
                    "不支持的 grant_type：" + grant);
        };
    }

    private Map<String, Object> clientCredentials(Credentials credentials,
                                                  Map<String, String> body) {
        OpenAppRepository.SecretRow app = authenticate(credentials);
        if (!app.supportsGrant("client_credentials")) {
            throw new OAuthError(403, "unauthorized_client", "该应用未开通 client_credentials");
        }
        String scope = field(body, "scope");
        if (scope == null || scope.isBlank()) {
            scope = app.scopes() == null ? "read" : app.scopes();
        } else if (!withinScopes(scope, app.scopes())) {
            throw new OAuthError(400, "invalid_scope", "申请的 scope 超出应用配置：" + app.scopes());
        }
        OpenTokenService.IssuedToken issued = tokenService.issue(app, scope);
        return tokenResponse(issued, null, null);
    }

    private Map<String, Object> authorizationCode(Credentials credentials,
                                                  Map<String, String> body) {
        OpenAppRepository.SecretRow app = authenticate(credentials);
        if (!app.supportsGrant("authorization_code")) {
            throw new OAuthError(403, "unauthorized_client", "该应用未开通 authorization_code");
        }
        String code = field(body, "code");
        String redirectUri = field(body, "redirectUri", "redirect_uri");
        if (code == null || redirectUri == null) {
            throw new OAuthError(400, "invalid_request", "缺少 code 或 redirect_uri");
        }
        OpenAuthCodeRepository.AuthCode authCode = authCodeRepository
                .consume(code, app.id(), redirectUri)
                .orElseThrow(() -> new OAuthError(400, "invalid_grant",
                        "授权码无效、已使用、已过期，或 redirect_uri 与授权时不一致"));
        OpenTokenService.IssuedToken issued = tokenService.issue(app, authCode.scopes(),
                authCode.userId(), authCode.username());
        String refresh = null;
        if (app.supportsGrant("refresh_token")) {
            refresh = OpenTokenService.newOpaqueToken();
            refreshTokenRepository.issue(OpenTokenService.hashToken(refresh), app.id(),
                    app.tenantId(), authCode.userId(), authCode.username(),
                    authCode.scopes(), properties.getRefreshTtl());
        }
        return tokenResponse(issued, refresh, authCode.username());
    }

    private Map<String, Object> refreshToken(Credentials credentials, Map<String, String> body) {
        OpenAppRepository.SecretRow app = authenticate(credentials);
        if (!app.supportsGrant("refresh_token")) {
            throw new OAuthError(403, "unauthorized_client", "该应用未开通 refresh_token");
        }
        String refresh = field(body, "refreshToken", "refresh_token");
        if (refresh == null) {
            throw new OAuthError(400, "invalid_request", "缺少 refresh_token");
        }
        OpenRefreshTokenRepository.RefreshRow row = refreshTokenRepository
                .consume(OpenTokenService.hashToken(refresh))
                .orElseThrow(() -> new OAuthError(400, "invalid_grant", "刷新令牌无效、已轮换或已过期"));
        if (row.appId() != app.id()) {
            throw new OAuthError(400, "invalid_grant", "刷新令牌不属于该应用");
        }
        // 用户被停用后不该还能续出一个有效令牌
        UserDirectory directory = userDirectories.getIfAvailable();
        if (directory != null && directory.load(row.userId()).isEmpty()) {
            throw new OAuthError(400, "invalid_grant", "授权用户已不可用，请重新授权");
        }
        OpenTokenService.IssuedToken issued = tokenService.issue(app, row.scopes(),
                row.userId(), row.username());
        String next = OpenTokenService.newOpaqueToken();
        refreshTokenRepository.issue(OpenTokenService.hashToken(next), app.id(), app.tenantId(),
                row.userId(), row.username(), row.scopes(), properties.getRefreshTtl());
        return tokenResponse(issued, next, row.username());
    }

    // ───────────────────────────── 业务接口 ─────────────────────────────

    @GetMapping("/ping")
    @OpenApi(code = "openapi.ping", name = "连通性探活", group = "基础能力",
            description = "检查 AppKey、签名、令牌与配额是否可用", scope = "read")
    public Map<String, Object> ping(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pong", true);
        result.put("appKey", request.getAttribute("openapi.appKey"));
        result.put("sandbox", request.getAttribute("openapi.sandbox"));
        result.put("time", Instant.now().toString());
        return result;
    }

    @GetMapping("/whoami")
    @OpenApi(code = "openapi.whoami", name = "查看应用身份", group = "基础能力",
            description = "返回当前应用、租户、scope 与沙盒标记", scope = "read")
    public Map<String, Object> whoami(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appKey", request.getAttribute("openapi.appKey"));
        result.put("tenant", request.getAttribute("openapi.tenant"));
        result.put("scope", request.getAttribute("openapi.scope"));
        result.put("sandbox", request.getAttribute("openapi.sandbox"));
        result.put("subject", request.getAttribute("openapi.subject"));
        result.put("actUser", request.getAttribute("openapi.actUser"));
        return result;
    }

    /**
     * 用户身份接口的标准示例：只有 authorization_code 换来的令牌能调。
     * 用机器令牌打到这里会得到 {@code 403 SUBJECT_REQUIRED}——
     * 这就是「注解区分主体」实际生效的样子。
     */
    @GetMapping("/userinfo")
    @OpenApi(code = "openapi.userinfo", name = "查看授权用户", group = "基础能力",
            description = "返回被代理用户的身份与角色，仅接受用户授权令牌",
            subject = OpenApiSubject.USER, scope = "read")
    public Map<String, Object> userinfo() {
        var user = CurrentUserProviderImpl.get();
        if (user == null) {
            // 过滤器已经拦过一次；走到这里说明上下文没下传，属于实现缺陷，不能静默返回空
            throw new OAuthError(500, "server_error", "用户上下文未下传");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.userId());
        result.put("username", user.username());
        result.put("tenant", user.tenantId());
        result.put("roles", user.roles());
        result.put("permissionCount", user.permissions().size());
        return result;
    }

    @PostMapping("/echo")
    @Idempotent(key = "openapi:echo")
    @OpenApi(code = "openapi.echo", name = "回显与幂等示例", group = "基础能力",
            description = "验证请求体签名与 Idempotency-Key 重放", scope = "write")
    public Map<String, Object> echo(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appKey", String.valueOf(request.getAttribute("openapi.appKey")));
        result.put("sandbox", Boolean.TRUE.equals(request.getAttribute("openapi.sandbox")));
        result.put("actUser", request.getAttribute("openapi.actUser"));
        result.put("echo", body == null ? Map.of() : body);
        return result;
    }

    // ───────────────────────────── 内部工具 ─────────────────────────────

    private record Credentials(String appKey, String clientSecret) {
    }

    private Credentials credentials(Map<String, String> body, String authorization) {
        if (authorization != null && authorization.startsWith("Basic ")) {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int split = decoded.indexOf(':');
            if (split <= 0) {
                throw new OAuthError(401, "invalid_client", "Basic 凭证格式不合法");
            }
            return new Credentials(decoded.substring(0, split), decoded.substring(split + 1));
        }
        String appKey = field(body, "appKey", "client_id");
        String secret = field(body, "clientSecret", "client_secret");
        if (appKey == null || secret == null) {
            throw new OAuthError(401, "invalid_client", "缺少应用凭证");
        }
        return new Credentials(appKey, secret);
    }

    private OpenAppRepository.SecretRow authenticate(Credentials credentials) {
        OpenAppRepository.SecretRow app = repository.findByAppKey(credentials.appKey())
                .orElseThrow(() -> new OAuthError(401, "invalid_client", "应用凭证无效"));
        if (!"ACTIVE".equals(app.status())) {
            throw new OAuthError(403, "unauthorized_client", "应用已停用");
        }
        String stored = cipher.decrypt(app.clientSecretEnc());
        if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                credentials.clientSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new OAuthError(401, "invalid_client", "应用凭证无效");
        }
        return app;
    }

    private Map<String, Object> tokenResponse(OpenTokenService.IssuedToken issued,
                                              String refreshToken, String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", issued.accessToken());
        result.put("token_type", "Bearer");
        result.put("expires_in", issued.expiresIn());
        result.put("scope", issued.scope());
        if (refreshToken != null) {
            result.put("refresh_token", refreshToken);
            result.put("refresh_expires_in", properties.getRefreshTtl().toSeconds());
        }
        if (username != null) {
            result.put("username", username);
        }
        return result;
    }

    private boolean withinScopes(String requested, String configured) {
        if (configured == null || configured.isBlank()) {
            return false;
        }
        List<String> allowed = tokenService.scopesOf(configured);
        return tokenService.scopesOf(requested).stream().allMatch(allowed::contains);
    }

    /** 同时兼容驼峰与 OAuth2 标准下划线字段。 */
    private static String field(Map<String, String> body, String... names) {
        if (body == null) {
            return null;
        }
        for (String name : names) {
            String value = body.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @ExceptionHandler(OAuthError.class)
    public ResponseEntity<Map<String, Object>> handleOAuthError(OAuthError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.error());
        body.put("error_description", error.getMessage());
        return ResponseEntity.status(HttpStatus.valueOf(error.status())).body(body);
    }
}
