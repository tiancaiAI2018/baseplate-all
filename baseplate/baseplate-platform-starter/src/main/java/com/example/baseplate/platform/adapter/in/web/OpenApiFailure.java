package com.example.baseplate.platform.adapter.in.web;

/** 开放平台鉴权失败：携带 HTTP 状态与稳定错误码，不暴露内部细节。 */
public final class OpenApiFailure extends RuntimeException {

    private final int status;
    private final String code;

    public OpenApiFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
