package com.example.baseplate.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级权限点校验：权限码来自模块清单注册的 permissions。
 * 由安全 starter 的切面统一执行，业务代码不写 if 判断。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    String[] value();
}
