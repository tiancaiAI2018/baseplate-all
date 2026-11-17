package com.example.baseplate.api.port.out;

/** ID 生成器：生产 UUIDv7，测试用可预测序列。 */
public interface IdGenerator {
    String next();
}
