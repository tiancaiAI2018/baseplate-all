package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.api.port.out.NonceStore;
import com.example.baseplate.api.port.out.RateLimiter;
import com.example.baseplate.api.port.out.UserDirectory;
import com.example.baseplate.platform.adapter.out.persistence.OpenApiCatalogRepository;
import com.example.baseplate.platform.adapter.out.persistence.OpenAppRepository;
import com.example.baseplate.platform.config.PlatformProperties;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.domain.OpenApiDef;
import com.example.baseplate.platform.signature.OpenApiSignature;
import com.example.baseplate.platform.token.OpenTokenService;
import com.example.baseplate.security.web.CurrentUserProviderImpl;
import com.example.baseplate.security.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * /openapi/** 的独立鉴权链，与内部 /api 完全隔离。
 *
 * <p>顺序写死：验签 → 时间窗 → nonce → 目录匹配 → 应用授权 → 令牌 → 主体 → scope
 * → 用户权限 → 配额 → 沙盒 → 下传用户上下文。每一步失败都带可区分的错误码，
 * 接对方能自己判断是签名、授权、主体还是权限的问题。</p>
 *
 * <p>主体判定是这套东西的关键：{@code subject=USER} 的接口必须是
 * authorization_code 换来的、带 uid 的令牌；client_credentials 令牌到这里直接
 * {@code SUBJECT_REQUIRED}，而不是让业务代码拿到一个空的「当前用户」。</p>
 *
 * <p>验签失败不写 nonce，避免攻击者用垃圾签名把 Redis 灌满。</p>
 */
public final class OpenApiAuthFilter extends OncePerRequestFilter {

    private static final String TOKEN_PATH = "/openapi/v1/oauth/token";
    private static final String SUPER_ROLE = "SUPER_ADMIN";

    private final OpenAppRepository repository;
    private final OpenApiCatalogRepository catalogRepository;
    private final SecretCipher cipher;
    private final OpenTokenService tokenService;
    private final NonceStore nonceStore;
    private final RateLimiter rateLimiter;
    private final PlatformProperties properties;
    /** 只在「代表用户调用」时需要；没装 identity 模块时为 null。 */
    private final UserDirectory userDirectory;

    public OpenApiAuthFilter(OpenAppRepository repository,
                             OpenApiCatalogRepository catalogRepository, SecretCipher cipher,
                             OpenTokenService tokenService, NonceStore nonceStore,
                             RateLimiter rateLimiter, PlatformProperties properties,
                             UserDirectory userDirectory) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.cipher = cipher;
        this.tokenService = tokenService;
        this.nonceStore = nonceStore;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.userDirectory = userDirectory;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/openapi/") || TOKEN_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String appKey = request.getHeader("X-App-Key");
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.ATTR));
        boolean sandbox = false;
        int status = 200;
        String resultCode = "OK";
        String tenant = null;
        String appName = null;
        String apiCode = null;
        String actUser = null;
        CachedBodyRequest cached = new CachedBodyRequest(request, request.getInputStream().readAllBytes());
        try {
            String timestamp = require(request, "X-Timestamp");
            String nonce = require(request, "X-Nonce");
            String signature = require(request, "X-Signature");
            if (appKey == null || appKey.isBlank()) {
                throw new OpenApiFailure(401, "APP_KEY_MISSING", "缺少 X-App-Key");
            }
            OpenAppRepository.SecretRow app = repository.findByAppKey(appKey)
                    .orElseThrow(() -> new OpenApiFailure(401, "APP_NOT_FOUND", "应用不存在"));
            appName = app.appName();
            // 交给访问审计：第三方请求没有登录用户，靠应用名和 Key 才查得出来是谁在调
            request.setAttribute("openapi.appKey", appKey);
            request.setAttribute("openapi.appName", appName);
            // 审计按租户隔离，第三方请求没有登录用户，只能靠应用所属租户落库
            request.setAttribute("openapi.appTenant", app.tenantId());
            if (!"ACTIVE".equals(app.status())) {
                throw new OpenApiFailure(403, "APP_DISABLED", "应用已停用");
            }
            if (!ipAllowed(app.ipWhitelist(), clientIp(request))) {
                throw new OpenApiFailure(403, "IP_NOT_ALLOWED", "来源 IP 不在白名单");
            }
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                throw new OpenApiFailure(401, "TIMESTAMP_INVALID", "时间戳格式不合法");
            }
            if (Math.abs(Instant.now().getEpochSecond() - ts) > properties.getTimestampWindow().toSeconds()) {
                throw new OpenApiFailure(401, "TIMESTAMP_EXPIRED", "请求时间戳超出允许窗口");
            }
            String canonical = OpenApiSignature.canonical(request.getMethod(), request.getRequestURI(),
                    request.getParameterMap(), timestamp, nonce, cached.body());
            String signingSecret = cipher.decrypt(app.signingSecretEnc());
            if (!OpenApiSignature.verify(signingSecret, canonical, signature)) {
                throw new OpenApiFailure(401, "SIGNATURE_INVALID", "请求签名不合法");
            }
            // 验签通过后才消费 nonce：失败请求不占用 nonce 空间
            if (!nonceStore.tryConsume(app.tenantId(), appKey, nonce, Duration.ofMinutes(10))) {
                throw new OpenApiFailure(409, "NONCE_REPLAYED", "nonce 已使用");
            }

            // ── 目录：先知道「这个路径要求什么」，再判请求满足不满足 ──
            OpenApiDef def = catalogRepository.match(request.getMethod(), request.getRequestURI())
                    .orElseThrow(() -> new OpenApiFailure(404, "API_NOT_FOUND",
                            "该路径不在开放 API 目录中"));
            apiCode = def.code();
            request.setAttribute("openapi.apiCode", apiCode);
            request.setAttribute("openapi.subject", def.subject().name());
            if (!catalogRepository.isGranted(app.id(), def.code())) {
                throw new OpenApiFailure(403, "API_NOT_GRANTED",
                        "该应用未被授权调用此 API：" + def.name());
            }

            // ── 令牌：纯签名模式可以不带；用户主体、或非纯签名模式必须带 ──
            OpenTokenService.TokenClaims claims = null;
            boolean requireToken = !"APPKEY_HMAC".equals(app.authMode()) || def.requiresToken();
            String authorization = request.getHeader("Authorization");
            boolean hasBearer = authorization != null && authorization.startsWith("Bearer ");
            if (requireToken && !hasBearer) {
                throw new OpenApiFailure(401, "TOKEN_REQUIRED", "该应用要求携带访问令牌");
            }
            if (hasBearer) {
                try {
                    claims = tokenService.verify(authorization.substring(7).trim());
                } catch (IllegalArgumentException e) {
                    throw new OpenApiFailure(401, "TOKEN_INVALID", e.getMessage());
                }
                if (!appKey.equals(claims.appKey())) {
                    throw new OpenApiFailure(401, "TOKEN_BINDING_MISMATCH", "令牌与应用不匹配");
                }
                request.setAttribute("openapi.scope", claims.scope());
            }

            // ── 主体：USER 接口只认「带 uid 的令牌」，机器令牌在这里被挡住 ──
            boolean userBound = claims != null && claims.userBound();
            if (def.subject() == OpenApiSubject.USER && !userBound) {
                throw new OpenApiFailure(403, "SUBJECT_REQUIRED",
                        "该接口必须代表真实用户，请使用 authorization_code 换取的令牌");
            }

            // ── scope：声明了按声明的判，没声明沿用「非 GET 必须 write」兜底 ──
            if (claims != null) {
                List<String> scopes = tokenService.scopesOf(claims.scope());
                String requiredScope = def.requiredScope();
                if (requiredScope != null && !requiredScope.isBlank()) {
                    if (!scopes.contains(requiredScope)) {
                        throw new OpenApiFailure(403, "SCOPE_DENIED",
                                "令牌缺少 " + requiredScope + " scope");
                    }
                } else if (!"GET".equalsIgnoreCase(request.getMethod()) && !scopes.contains("write")) {
                    throw new OpenApiFailure(403, "SCOPE_DENIED", "令牌缺少 write scope");
                }
            }

            // ── 用户权限：应用被授权 ≠ 这个用户有权干这件事 ──
            CurrentUser subjectUser = null;
            if (userBound && def.userAllowed()) {
                if (userDirectory == null) {
                    throw new OpenApiFailure(503, "USER_DIRECTORY_UNAVAILABLE",
                            "服务端未装配用户目录，无法校验用户身份");
                }
                UserDirectory.Snapshot snapshot = userDirectory.load(claims.userId())
                        .orElseThrow(() -> new OpenApiFailure(401, "USER_NOT_FOUND",
                                "授权用户不存在或已停用"));
                if (!app.tenantId().equals(snapshot.tenantId())) {
                    throw new OpenApiFailure(403, "TENANT_MISMATCH", "用户与应用不属于同一租户");
                }
                String permission = def.requiredPermission();
                boolean allowed = permission == null || permission.isBlank()
                        || snapshot.permissions().contains(permission)
                        || snapshot.roles().contains(SUPER_ROLE);
                if (!allowed) {
                    throw new OpenApiFailure(403, "USER_PERMISSION_DENIED",
                            "该用户缺少权限：" + permission);
                }
                subjectUser = new CurrentUser(snapshot.userId(), snapshot.tenantId(),
                        snapshot.username(), snapshot.roles(), snapshot.permissions(), false);
                actUser = snapshot.username();
                request.setAttribute("openapi.actUser", actUser);
            }

            // ── 配额：走到这里说明签名、授权、主体都合法了，才计入配额 ──
            if (!rateLimiter.tryAcquire(app.tenantId(), "open:qps:" + appKey,
                    Math.max(1, app.qpsLimit()), Duration.ofSeconds(1))) {
                throw new OpenApiFailure(429, "QPS_LIMITED", "请求频率超限");
            }
            if (!rateLimiter.tryAcquire(app.tenantId(), "open:day:" + appKey,
                    Math.max(1, app.dayLimit()), Duration.ofDays(1))) {
                throw new OpenApiFailure(429, "DAILY_QUOTA_EXCEEDED", "当日调用配额已用尽");
            }

            // ── 沙盒 ──
            sandbox = "true".equalsIgnoreCase(request.getHeader("X-Sandbox"));
            if (sandbox && (!app.sandboxEnabled() || !properties.isSandboxEnabled())) {
                throw new OpenApiFailure(403, "SANDBOX_DISABLED", "该应用未开启沙盒");
            }
            if (sandbox && !def.sandboxAllowed()) {
                throw new OpenApiFailure(403, "SANDBOX_NOT_SUPPORTED", "该接口不支持沙盒调用");
            }
            tenant = sandbox ? properties.getSandboxTenantPrefix() + app.tenantId() : app.tenantId();
            request.setAttribute("openapi.tenant", tenant);
            request.setAttribute("openapi.sandbox", sandbox);

            // ── 下传用户上下文：业务代码零改动，@RequiresPermission 与数据权限直接生效 ──
            if (subjectUser != null) {
                CurrentUserProviderImpl.set(subjectUser);
            }
            chain.doFilter(cached, response);
        } catch (OpenApiFailure e) {
            status = e.status();
            resultCode = e.code();
            writeProblem(response, e, request);
        } catch (RuntimeException e) {
            status = 500;
            resultCode = "INTERNAL_ERROR";
            writeProblem(response, new OpenApiFailure(500, "INTERNAL_ERROR", "服务内部错误"), request);
        } finally {
            CurrentUserProviderImpl.clear();
            try {
                repository.log(tenant, appKey, appName, sandbox, traceId, request.getMethod(),
                        request.getRequestURI(), status, resultCode,
                        System.currentTimeMillis() - start, clientIp(request), apiCode,
                        request.getHeader("User-Agent"), actUser);
            } catch (RuntimeException ignored) {
                // 审计日志失败不能反过来影响响应
            }
        }
    }

    private String require(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            throw new OpenApiFailure(401, "SIGNATURE_HEADERS_MISSING", "缺少签名请求头：" + header);
        }
        return value;
    }

    private boolean ipAllowed(String whitelist, String ip) {
        if (whitelist == null || whitelist.isBlank() || "*".equals(whitelist.trim())) {
            return true;
        }
        return Arrays.stream(whitelist.split(","))
                .map(String::trim)
                .anyMatch(item -> item.equals(ip));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeProblem(HttpServletResponse response, OpenApiFailure e,
                              HttpServletRequest request) throws IOException {
        response.setStatus(e.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String traceId = String.valueOf(request.getAttribute(TraceIdFilter.ATTR));
        String body = "{\"type\":\"about:blank\",\"title\":\"" + e.code() + "\",\"status\":"
                + e.status() + ",\"code\":\"" + e.code() + "\",\"detail\":\"" + e.getMessage()
                + "\",\"traceId\":\"" + traceId + "\"}";
        response.getWriter().write(body);
    }
}
