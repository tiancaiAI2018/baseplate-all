package com.example.baseplate.api.port.out;

import java.time.Instant;

/** 可注入时钟：测试用固定时钟，生产用系统时钟。 */
public interface Clock {
    Instant now();
}
