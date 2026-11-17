package com.example.baseplate.api.exception;

/** 已认证但无权限：对外 403，不泄漏权限模型细节。 */
public class ForbiddenException extends BaseplateException {

    public ForbiddenException(String message) {
        super("FORBIDDEN", message);
    }
}
