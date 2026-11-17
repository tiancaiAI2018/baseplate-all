package com.example.baseplate.platform.domain;

import java.time.OffsetDateTime;

/** 开放平台应用：对外只暴露脱敏后的元数据，密钥单独一次性返回。 */
public record OpenApp(
        long id,
        String tenantId,
        String appKey,
        String appName,
        /** 请求侧要求：是否强制签名 / 是否强制令牌，与「能怎么拿令牌」是两件事。 */
        String authMode,
        /** 令牌获取方式，逗号分隔：client_credentials / authorization_code / refresh_token。 */
        String grantTypes,
        String scopes,
        String ipWhitelist,
        int qpsLimit,
        int dayLimit,
        String status,
        boolean sandboxEnabled,
        /** 授权码流程的回调白名单，换行或逗号分隔。 */
        String redirectUris,
        OffsetDateTime createdAt) {
}
