package com.example.baseplate.security.web;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;

import java.util.function.Supplier;

/**
 * 请求级当前用户：ThreadLocal 生命周期严格由过滤器 set/clear，
 * 虚拟线程下一次请求一个载体线程，不存在线程池串号。
 */
public final class CurrentUserProviderImpl implements CurrentUserProvider {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    @Override
    public CurrentUser current() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new com.example.baseplate.api.exception.AuthenticationException("未认证");
        }
        return user;
    }

    @Override
    public <T> T withUser(CurrentUser user, Supplier<T> body) {
        CurrentUser previous = HOLDER.get();
        HOLDER.set(user);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
