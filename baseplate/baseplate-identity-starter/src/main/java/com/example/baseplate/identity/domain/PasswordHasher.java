package com.example.baseplate.identity.domain;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 口令哈希：BCrypt；算法升级时换实现，不动调用方。 */
public final class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String hash) {
        return hash != null && encoder.matches(rawPassword, hash);
    }
}
