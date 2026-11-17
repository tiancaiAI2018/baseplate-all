package com.example.baseplate.platform.adapter.in.web;

/** OAuth2 标准错误结构：接对方按 {@code error} 分支处理，不必解析中文 detail。 */
public final class OAuthError extends RuntimeException {

    private final int status;
    private final String error;

    public OAuthError(int status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }
}
