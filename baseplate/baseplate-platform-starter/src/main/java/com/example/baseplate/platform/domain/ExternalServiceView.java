package com.example.baseplate.platform.domain;

import java.util.List;

/** 控制台外部服务详情（不含凭证密文）。 */
public record ExternalServiceView(
        String code,
        String name,
        String baseUrl,
        String authType,
        String credentialHeader,
        boolean credentialConfigured,
        String status,
        List<ExternalEndpointView> endpoints) {
}
