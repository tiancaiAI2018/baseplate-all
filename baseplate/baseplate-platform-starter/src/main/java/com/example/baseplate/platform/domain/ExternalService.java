package com.example.baseplate.platform.domain;

import java.util.List;
import java.util.Map;

/**
 * 第三方服务注册记录。凭证只存密文，且只在网关内部解密。
 *
 * @param authType NONE / API_KEY / BEARER
 * @param credentialHeader 凭证注入的请求头名（API_KEY 时生效）
 * @param credentialEnc    加密后的凭证值
 */
public record ExternalService(
        String code,
        String tenantId,
        String baseUrl,
        String authType,
        String credentialHeader,
        String credentialEnc,
        List<ExternalEndpoint> endpoints,
        Map<String, String> staticHeaders) {
}
