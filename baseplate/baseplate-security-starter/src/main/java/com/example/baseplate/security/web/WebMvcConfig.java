package com.example.baseplate.security.web;

import com.example.baseplate.api.port.out.IdempotencyStore;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import com.example.baseplate.api.port.out.RateLimiter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/** 拦截器顺序固定：限流 → 幂等 → 一次性链接。 */
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimiter rateLimiter;
    private final IdempotencyStore idempotencyStore;
    private final OneTimeTokenStore oneTimeTokenStore;

    public WebMvcConfig(RateLimiter rateLimiter, IdempotencyStore idempotencyStore,
                        OneTimeTokenStore oneTimeTokenStore) {
        this.rateLimiter = rateLimiter;
        this.idempotencyStore = idempotencyStore;
        this.oneTimeTokenStore = oneTimeTokenStore;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter)).order(10);
        registry.addInterceptor(new IdempotencyInterceptor(idempotencyStore, Duration.ofHours(24)))
                .order(20);
        registry.addInterceptor(new OneTimeLinkInterceptor(oneTimeTokenStore)).order(30);
    }
}
