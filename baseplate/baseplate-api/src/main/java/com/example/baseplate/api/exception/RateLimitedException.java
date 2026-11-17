package com.example.baseplate.api.exception;

/** 触发限流：对外 429，并带 Retry-After。 */
public class RateLimitedException extends BaseplateException {
    public RateLimitedException(String message) {
        super("RATE_LIMITED", message);
    }
}
