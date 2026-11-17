package com.example.baseplate.identity.adapter.in.web;

import com.example.baseplate.api.exception.AuthenticationException;
import com.example.baseplate.api.model.AuthResult;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;
import com.example.baseplate.identity.application.AuthenticationService;
import com.example.baseplate.identity.application.PasswordService;
import com.example.baseplate.security.jwt.JwtClaims;
import com.example.baseplate.security.jwt.TokenBlacklist;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 认证入口：登录、注销、当前用户、改密。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final PasswordService passwordService;
    private final TokenBlacklist blacklist;
    private final CurrentUserProvider currentUser;
    private final IdentityDao dao;

    public AuthController(AuthenticationService authenticationService, PasswordService passwordService,
                          TokenBlacklist blacklist, CurrentUserProvider currentUser, IdentityDao dao) {
        this.authenticationService = authenticationService;
        this.passwordService = passwordService;
        this.blacklist = blacklist;
        this.currentUser = currentUser;
        this.dao = dao;
    }

    @PostMapping("/login")
    public AuthResult login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authenticationService.login(request.username(), request.password(),
                request.tenant(), clientIp(servletRequest));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        Object claims = request.getAttribute("baseplate.claims");
        if (claims instanceof JwtClaims jwtClaims) {
            blacklist.revoke(jwtClaims);
        }
        return Map.of("loggedOut", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser user = currentUser.current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.userId());
        result.put("tenantId", user.tenantId());
        result.put("username", user.username());
        result.put("roles", user.roles());
        result.put("permissions", user.permissions());
        result.put("mustChangePassword", user.mustChangePassword());
        dao.findUserById(Long.parseLong(user.userId())).ifPresent(row -> {
            result.put("displayName", row.displayName());
            result.put("orgId", row.orgId());
        });
        return result;
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest request) {
        CurrentUser user = currentUser.current();
        passwordService.changeOwnPassword(Long.parseLong(user.userId()),
                request.oldPassword(), request.newPassword());
        return Map.of("changed", true, "reloginRequired", true);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(String username, String password, String tenant) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }
}
