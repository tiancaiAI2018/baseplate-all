package com.example.baseplate.platform.application;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.platform.adapter.out.persistence.OpenApiCatalogRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenAppRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenRefreshTokenRepository;
import com.example.baseplate.platform.config.PlatformProperties;
import com.example.baseplate.platform.crypto.SecretCipher;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放平台应用管理：密钥明文只在创建与轮换时返回一次，之后只能重置。
 * client_secret（换 token）与 signing_secret（签名）分开，禁止复用。
 */
public final class OpenAppService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 请求侧要求：是否强制签名 / 是否强制令牌。 */
    private static final List<String> AUTH_MODES = List.of("APPKEY_HMAC", "OAUTH2_HMAC", "OAUTH2_ONLY");
    /** 令牌获取方式：一个应用可以同时开通多种，互不排斥。 */
    private static final List<String> GRANT_TYPES = List.of(
            "client_credentials", "authorization_code", "refresh_token");

    private final OpenAppRepository repository;
    private final OpenApiCatalogRepository catalogRepository;
    private final OpenRefreshTokenRepository refreshTokenRepository;
    private final SecretCipher cipher;
    private final CurrentUserProvider currentUser;
    private final PlatformProperties properties;

    public OpenAppService(OpenAppRepository repository, OpenApiCatalogRepository catalogRepository,
                          OpenRefreshTokenRepository refreshTokenRepository,
                          SecretCipher cipher, CurrentUserProvider currentUser,
                          PlatformProperties properties) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.cipher = cipher;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    public record AppView(long id, String appKey, String appName, String authMode,
                          List<String> grantTypes, List<String> scopes, String ipWhitelist,
                          int qpsLimit, int dayLimit, String status, boolean sandboxEnabled,
                          List<String> redirectUris, int grantedApiCount) {
    }

    public record CreatedApp(AppView app, String clientSecret, String signingSecret) {
    }

    /** 新建 / 编辑的入参；参数多，用记录承载避免位置错乱。 */
    public record AppCommand(String appName, String authMode, List<String> grantTypes,
                             List<String> scopes, String ipWhitelist, int qpsLimit, int dayLimit,
                             boolean sandbox, List<String> redirectUris, String status) {
    }

    public List<AppView> list() {
        String tenant = currentUser.current().tenantId();
        return repository.list(tenant).stream().map(this::toView).toList();
    }

    public CreatedApp create(AppCommand cmd) {
        String tenant = currentUser.current().tenantId();
        String appKey = "ak_" + randomHex(12);
        String clientSecret = "cs_" + randomHex(24);
        String signingSecret = "ss_" + randomHex(24);
        String mode = normaliseAuthMode(cmd.authMode());
        List<String> grants = normaliseGrants(cmd.grantTypes());
        List<String> redirects = normaliseRedirects(cmd.redirectUris(), grants);
        long id = repository.insert(new OpenAppRepository.NewApp(tenant, appKey,
                cmd.appName() == null || cmd.appName().isBlank() ? appKey : cmd.appName(),
                cipher.encrypt(clientSecret), cipher.encrypt(signingSecret), mode,
                String.join(",", grants),
                cmd.scopes() == null || cmd.scopes().isEmpty() ? "read,write" : String.join(",", cmd.scopes()),
                cmd.ipWhitelist(), Math.max(1, cmd.qpsLimit()), Math.max(1, cmd.dayLimit()),
                cmd.sandbox(), redirects.isEmpty() ? null : String.join("\n", redirects)));
        // 新应用只默认开通内置基础能力；业务接口必须显式勾选
        catalogRepository.grant(id, catalogRepository.defaultClientCodes());
        AppView view = repository.find(tenant, id).map(this::toView).orElseThrow();
        return new CreatedApp(view, clientSecret, signingSecret);
    }

    public AppView update(long id, AppCommand cmd) {
        String tenant = currentUser.current().tenantId();
        repository.find(tenant, id).orElseThrow(() -> new IllegalArgumentException("应用不存在"));
        List<String> grants = normaliseGrants(cmd.grantTypes());
        List<String> redirects = normaliseRedirects(cmd.redirectUris(), grants);
        repository.update(id, new OpenAppRepository.AppUpdate(
                cmd.appName(), normaliseAuthMode(cmd.authMode()), String.join(",", grants),
                cmd.scopes() == null ? "read" : String.join(",", cmd.scopes()),
                cmd.ipWhitelist(), Math.max(1, cmd.qpsLimit()), Math.max(1, cmd.dayLimit()),
                cmd.status() == null ? "ACTIVE" : cmd.status(), cmd.sandbox(),
                redirects.isEmpty() ? null : String.join("\n", redirects)));
        // 关掉授权码能力时，之前发给用户的刷新令牌必须一起作废，否则关不干净
        if (!grants.contains("authorization_code")) {
            refreshTokenRepository.revokeAllByApp(id);
        }
        return repository.find(tenant, id).map(this::toView).orElseThrow();
    }

    /**
     * 软删除：只置 DELETED，不物理删行。
     * 调用日志要能长期追溯到「哪个应用调过什么」，行删了日志就断了外键语义。
     */
    public void delete(long id) {
        String tenant = currentUser.current().tenantId();
        repository.find(tenant, id).orElseThrow(() -> new IllegalArgumentException("应用不存在"));
        repository.markDeleted(tenant, id);
        refreshTokenRepository.revokeAllByApp(id);
    }

    public Map<String, Object> rotate(long id) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = repository.findSecrets(id)
                .filter(row -> row.tenantId().equals(tenant))
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));
        String clientSecret = "cs_" + randomHex(24);
        String signingSecret = "ss_" + randomHex(24);
        repository.rotateSecrets(app.id(), cipher.encrypt(clientSecret), cipher.encrypt(signingSecret));
        refreshTokenRepository.revokeAllByApp(id);
        return Map.of("appKey", app.appKey(), "clientSecret", clientSecret,
                "signingSecret", signingSecret, "note", "旧密钥立即失效，已签发的刷新令牌一并撤销");
    }

    public List<Map<String, Object>> logs(long id, int limit) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = require(tenant, id);
        return repository.logs(tenant, app.appKey(), Math.min(500, Math.max(1, limit)));
    }

    public List<Map<String, Object>> usage(long id, int days) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = require(tenant, id);
        return repository.usage(tenant, app.appKey(), Math.min(90, Math.max(1, days)));
    }

    /** 控制台沙盒测试：服务端生成待签串与签名，并自校验一遍，证明链路能通。 */
    public Map<String, Object> sandboxTest(long id, String method, String path,
                                           Map<String, Object> body) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = require(tenant, id);
        String signingSecret = cipher.decrypt(app.signingSecretEnc());
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String nonce = randomHex(8);
        String httpMethod = method == null ? "GET" : method.toUpperCase();
        String requestPath = path == null || path.isBlank() ? "/openapi/v1/ping" : path;
        byte[] payload = body == null ? new byte[0] : toJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String canonical = com.example.baseplate.platform.signature.OpenApiSignature.canonical(
                httpMethod, requestPath, Map.of(), timestamp, nonce, payload);
        String signature = com.example.baseplate.platform.signature.OpenApiSignature.sign(
                signingSecret, canonical);
        boolean verified = com.example.baseplate.platform.signature.OpenApiSignature.verify(
                signingSecret, canonical, signature);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-App-Key", app.appKey());
        headers.put("X-Timestamp", timestamp);
        headers.put("X-Nonce", nonce);
        headers.put("X-Signature", signature);
        headers.put("X-Sandbox", "true");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", httpMethod);
        result.put("path", requestPath);
        result.put("canonical", canonical);
        result.put("headers", headers);
        result.put("signatureVerified", verified);
        result.put("curl", "curl -X " + httpMethod + " 'http://localhost:8080" + requestPath
                + "' -H 'X-App-Key: " + app.appKey() + "' -H 'X-Timestamp: " + timestamp
                + "' -H 'X-Nonce: " + nonce + "' -H 'X-Signature: " + signature
                + "' -H 'X-Sandbox: true'");
        result.put("note", "测试通过只代表签名链正确；真实调用请用沙盒凭证");
        return result;
    }

    private OpenAppRepository.SecretRow require(String tenant, long id) {
        return repository.findSecrets(id)
                .filter(row -> row.tenantId().equals(tenant))
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));
    }

    private AppView toView(com.example.baseplate.platform.domain.OpenApp app) {
        return new AppView(app.id(), app.appKey(), app.appName(), app.authMode(),
                splitCsv(app.grantTypes()), splitCsv(app.scopes()), app.ipWhitelist(),
                app.qpsLimit(), app.dayLimit(), app.status(), app.sandboxEnabled(),
                splitLines(app.redirectUris()), catalogRepository.countGrants(app.id()));
    }

    public List<com.example.baseplate.platform.domain.OpenApiDef> catalog() {
        return catalogRepository.catalog();
    }

    public Map<String, Object> apis(long id) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = require(tenant, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appKey", app.appKey());
        result.put("appName", app.appName());
        result.put("grantTypes", splitCsv(app.grantTypes()));
        result.put("catalog", catalogRepository.catalog());
        result.put("granted", catalogRepository.grantedCodes(app.id()));
        return result;
    }

    /**
     * 授权时挡住「应用没能力却勾了接口」：需要用户主体的接口，应用必须开通授权码流程。
     * 前端会置灰，但后端必须再拦一次——页面只是提示，不是约束。
     */
    public void grantApis(long id, List<String> codes) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = require(tenant, id);
        if (codes != null && !app.supportsGrant("authorization_code")) {
            List<String> blocked = catalogRepository.catalog().stream()
                    .filter(def -> codes.contains(def.code()))
                    .filter(def -> def.subject() == com.example.baseplate.api.model.OpenApiSubject.USER)
                    .map(com.example.baseplate.platform.domain.OpenApiDef::name)
                    .toList();
            if (!blocked.isEmpty()) {
                throw new IllegalArgumentException("以下接口需要用户授权令牌，请先为该应用开通 authorization_code："
                        + String.join("、", blocked));
            }
        }
        catalogRepository.replaceGrants(app.id(), codes);
    }

    public Map<String, Object> logDetail(long id, long logId) {
        String tenant = currentUser.current().tenantId();
        OpenAppRepository.SecretRow app = require(tenant, id);
        Map<String, Object> detail = repository.logDetail(tenant, app.appKey(), logId);
        if (detail == null) {
            throw new IllegalArgumentException("调用记录不存在");
        }
        return detail;
    }

    private static String normaliseAuthMode(String authMode) {
        String mode = authMode == null || authMode.isBlank() ? "OAUTH2_HMAC" : authMode.trim();
        if (!AUTH_MODES.contains(mode)) {
            throw new IllegalArgumentException("不支持的认证模式：" + mode);
        }
        return mode;
    }

    private static List<String> normaliseGrants(List<String> grantTypes) {
        List<String> grants = new ArrayList<>();
        if (grantTypes != null) {
            for (String grant : grantTypes) {
                if (grant == null || grant.isBlank()) {
                    continue;
                }
                String trimmed = grant.trim();
                if (!GRANT_TYPES.contains(trimmed)) {
                    throw new IllegalArgumentException("不支持的令牌获取方式：" + trimmed);
                }
                if (!grants.contains(trimmed)) {
                    grants.add(trimmed);
                }
            }
        }
        if (grants.isEmpty()) {
            grants.add("client_credentials");
        }
        // refresh_token 是 authorization_code 的续期手段，不能单独存在
        if (grants.contains("refresh_token") && !grants.contains("authorization_code")) {
            throw new IllegalArgumentException("refresh_token 必须与 authorization_code 一起开通");
        }
        return grants;
    }

    private static List<String> normaliseRedirects(List<String> redirectUris, List<String> grants) {
        List<String> result = new ArrayList<>();
        if (redirectUris != null) {
            for (String uri : redirectUris) {
                if (uri == null || uri.isBlank()) {
                    continue;
                }
                String trimmed = uri.trim();
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    throw new IllegalArgumentException("回调地址必须是 http(s) 绝对地址：" + trimmed);
                }
                if (trimmed.contains("*")) {
                    throw new IllegalArgumentException("回调地址不支持通配，必须精确填写：" + trimmed);
                }
                if (!result.contains(trimmed)) {
                    result.add(trimmed);
                }
            }
        }
        if (grants.contains("authorization_code") && result.isEmpty()) {
            throw new IllegalArgumentException("开通授权码流程必须至少配置一个回调地址");
        }
        return result;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\s*,\\s*"));
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[\\r\\n,]+"))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (RuntimeException e) {
            return "{}";
        }
    }
}
