package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.CurrentUser;

import java.util.function.Supplier;

/** 当前用户上下文：withUser 显式声明跨线程/虚拟线程传递语义，禁止隐式 ThreadLocal。 */
public interface CurrentUserProvider {
    CurrentUser current();

    <T> T withUser(CurrentUser user, Supplier<T> body);
}
