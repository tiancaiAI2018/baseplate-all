package com.example.baseplate.api.port.out;

import java.time.Duration;

/** 签名 nonce 去重：原子 SET NX EX；key 含 tenant + appKey 维度。 */
public interface NonceStore {
    boolean tryConsume(String tenant, String appKey, String nonce, Duration ttl);
}
