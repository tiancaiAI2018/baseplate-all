package com.example.baseplate.api.port.out;

import java.time.Duration;
import java.util.Optional;

/** 一次性链接：原子消费；purpose 必须参与比较，防跨用途重放。 */
public interface OneTimeTokenStore {
    String issue(String purpose, String subject, Duration ttl);

    Optional<String> consume(String purpose, String jti);
}
