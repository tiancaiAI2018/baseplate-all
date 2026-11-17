package com.example.baseplate.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一次性链接：purpose 必须参与签名，防跨用途重放。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneTimeLink {
    String purpose() default "";
}
