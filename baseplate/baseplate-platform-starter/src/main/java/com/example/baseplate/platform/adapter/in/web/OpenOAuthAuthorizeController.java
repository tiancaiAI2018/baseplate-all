package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.platform.adapter.out.persistence.OpenAppRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenAuthCodeRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenRefreshTokenRepository;
import com.example.baseplate.platform.config.PlatformProperties;
import com.example.baseplate.platform.token.OpenTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权码流程的「同意页」后端。
 *
 * <p>拆成两步而不是一个 GET 直接发码：</p>
 * <ol>
 *   <li>{@code GET /context} 先让前端把「哪个应用、要什么权限、之前授过没」展示清楚；</li>
 *   <li>{@code POST /authorize} 用户点了同意才发码。</li>
 * </ol>
 * <p>如果做成一个 GET，浏览器预取、聊天软件预览链接都会替用户「点同意」，
 * 等于绕过了知情同意。OAuth2 规范要求这一步必须是用户交互，原因就在这。</p>
 *
 * <p>回调地址必须精确匹配白名单且先校验、再重定向：否则就是开放重定向漏洞，
 * 攻击者把自己的站点填进 redirect_uri 就能拿到别人的授权码。</p>
 */
@RestController
@RequestMapping("/api/platform/oauth")
public class OpenOAuthAuthorizeController {

    private static final Map<String, String> SCOPE_LABELS = Map.of(
            "read", "读取业务数据",
            "write", "新增与修改业务数据");

    private final OpenAppRepository repository;
    private final OpenAuthCodeRepository authCodeRepository;
    private final OpenRefreshTokenRepository refreshTokenRepository;
    private final OpenTokenService tokenService;
    private final CurrentUserProvider currentUser;
    private final PlatformProperties properties;

    public OpenOAuthAuthorizeController(OpenAppRepository repository,
                                        OpenAuthCodeRepository authCodeRepository,
                                        OpenRefreshTokenRepository refreshTokenRepository,
                                        OpenTokenService tokenService,
                                        CurrentUserProvider currentUser,
                                        PlatformProperties properties) {
        this.repository = repository;
        this.authCodeRepository = authCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenService = tokenService;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /**
     * 授权页要展示的内容。不要求 openapi:* 权限——授权的主体是普通用户，
     * 不是管理员；只要求已登录。
     */
    @GetMapping("/authorize/context")
    public Map<String, Object> context(@RequestParam String clientId,
                                       @RequestParam(required = false) String scope,
                                       @RequestParam(required = false) String redirectUri) {
        CurrentUser user = currentUser.current();
        OpenAppRepository.SecretRow app = requireApp(clientId);
        List<String> requested = resolveScopes(scope, app);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appName", app.appName());
        result.put("clientId", app.appKey());
        result.put("requestedScopes", requested);
        result.put("scopeDescriptions", describe(requested));
        result.put("redirectUriAllowed", redirectUri != null && app.redirectUriList().contains(redirectUri));
        result.put("tenantOk", app.tenantId().equals(user.tenantId()));
        result.put("authorizeSupported", app.supportsGrant("authorization_code"));
        result.put("previouslyGranted", refreshTokenRepository
                .latestActive(app.id(), user.userId()).isPresent());
        result.put("currentUser", user.username());
        return result;
    }

    /** 用户点「同意」或「拒绝」；两种结果都带 state 重定向回去。 */
    @PostMapping("/authorize")
    @AuditLog(action = "openapi:user-authorize", resource = "open_app")
    public Map<String, Object> authorize(@RequestBody AuthorizeRequest request) {
        CurrentUser user = currentUser.current();
        OpenAppRepository.SecretRow app = requireApp(request.clientId());
        if (!app.supportsGrant("authorization_code")) {
            throw new IllegalArgumentException("该应用未开通授权码流程");
        }
        if (!app.tenantId().equals(user.tenantId())) {
            throw new IllegalArgumentException("该应用不属于当前租户，不能授权");
        }
        if (request.redirectUri() == null || !app.redirectUriList().contains(request.redirectUri())) {
            // 不在白名单时绝不能重定向——那就是开放重定向
            throw new IllegalArgumentException("回调地址不在该应用的白名单内");
        }
        boolean approved = !Boolean.FALSE.equals(request.approve());
        if (!approved) {
            refreshTokenRepository.revokeAll(app.id(), user.userId());
            return Map.of("redirectUrl", buildRedirect(request.redirectUri(), request.state(),
                    null, "access_denied"));
        }
        List<String> scopes = resolveScopes(request.scope(), app);
        String code = OpenTokenService.newOpaqueToken();
        authCodeRepository.issue(code, app.id(), app.tenantId(), user.userId(), user.username(),
                String.join(",", scopes), request.redirectUri(), properties.getAuthCodeTtl());
        return Map.of("redirectUrl", buildRedirect(request.redirectUri(), request.state(), code, null));
    }

    /** 已授权用户的授权清单，供用户主动撤回。 */
    @GetMapping("/grants")
    public List<Map<String, Object>> grants() {
        CurrentUser user = currentUser.current();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var app : repository.list(user.tenantId())) {
            refreshTokenRepository.latestActive(app.id(), user.userId()).ifPresent(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("appId", app.id());
                item.put("appName", app.appName());
                item.put("clientId", app.appKey());
                item.put("scopes", row.scopes());
                item.put("expiresAt", row.expiresAt());
                result.add(item);
            });
        }
        return result;
    }

    /** 用户撤回对某应用的授权：刷新令牌全撤，已签发的 access_token 到期自然失效。 */
    @PostMapping("/revoke")
    @AuditLog(action = "openapi:revoke-user-grant", resource = "open_app")
    public Map<String, Object> revoke(@RequestBody RevokeRequest request) {
        CurrentUser user = currentUser.current();
        OpenAppRepository.SecretRow app = requireApp(request.clientId());
        int revoked = refreshTokenRepository.revokeAll(app.id(), user.userId());
        return Map.of("revoked", revoked);
    }

    private OpenAppRepository.SecretRow requireApp(String clientId) {
        return repository.findByAppKey(clientId)
                .filter(app -> "ACTIVE".equals(app.status()))
                .orElseThrow(() -> new IllegalArgumentException("应用不存在或已停用"));
    }

    private List<String> resolveScopes(String scope, OpenAppRepository.SecretRow app) {
        List<String> allowed = app.scopes() == null || app.scopes().isBlank()
                ? List.of() : List.of(app.scopes().split("\\s*,\\s*"));
        if (scope == null || scope.isBlank()) {
            return allowed;
        }
        List<String> requested = tokenService.scopesOf(scope);
        for (String item : requested) {
            if (!allowed.contains(item)) {
                throw new IllegalArgumentException("申请的 scope 超出该应用配置：" + item);
            }
        }
        return requested;
    }

    private List<Map<String, String>> describe(List<String> scopes) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String scope : scopes) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("scope", scope);
            item.put("label", SCOPE_LABELS.getOrDefault(scope, scope));
            result.add(item);
        }
        return result;
    }

    private String buildRedirect(String redirectUri, String state, String code, String error) {
        StringBuilder url = new StringBuilder(redirectUri);
        char separator = redirectUri.contains("?") ? '&' : '?';
        if (code != null) {
            url.append(separator).append("code=").append(encode(code));
            separator = '&';
        }
        if (error != null) {
            url.append(separator).append("error=").append(encode(error));
            separator = '&';
        }
        if (state != null && !state.isBlank()) {
            url.append(separator).append("state=").append(encode(state));
        }
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record AuthorizeRequest(String clientId, String redirectUri, String scope,
                                   String state, Boolean approve) {
    }

    public record RevokeRequest(String clientId) {
    }
}
