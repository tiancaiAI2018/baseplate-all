package com.example.baseplate.api.exception;

/** 认证失败：对外统一 401，不区分具体失败原因。 */
public class AuthenticationException extends BaseplateException {
    public AuthenticationException(String message) {
        super("AUTH_FAILED", message);
    }
}
