package com.example.baseplate.api.model;

/** 登录结果：mustChangePassword 为 true 时只放行改密/登出/取当前用户三个端点。 */
public record AuthResult(String accessToken, String refreshToken, long expiresInSeconds,
                         boolean mustChangePassword) {
}
