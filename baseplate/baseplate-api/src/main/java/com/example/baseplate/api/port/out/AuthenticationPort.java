package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.AuthResult;

import java.time.Instant;

/** 认证端口：JWT 签发 + 注销黑名单。 */
public interface AuthenticationPort {
    AuthResult login(String username, String rawPassword, String tenant);

    void logout(String jti, Instant expiresAt);
}
