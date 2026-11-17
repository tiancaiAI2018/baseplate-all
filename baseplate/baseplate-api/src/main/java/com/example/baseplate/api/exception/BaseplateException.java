package com.example.baseplate.api.exception;

/** 基盘异常基类：携带稳定错误码，供 ProblemDetail 映射。 */
public class BaseplateException extends RuntimeException {

    private final String code;

    public BaseplateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
