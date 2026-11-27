package com.example.baseplate.api.port.out;

import java.util.Map;

/**
 * 外部服务调用网关：URL 与凭证都来自平台注册表，调用方只能传 serviceCode / endpointCode / 参数。
 * 模型永远拿不到凭证和任意 URL，因此不存在 SSRF 与密钥泄露通道。
 */
public interface ExternalServiceGateway {

    /**
     * @param tenant       租户，用于选择租户级凭证
     * @param serviceCode  服务编码
     * @param endpointCode 端点编码
     * @param params       业务参数（GET 拼 query，POST 写 JSON body，同时用于替换 {path} 占位符）
     * @param headers      额外请求头（如 Idempotency-Key）
     * @return 第三方响应体
     */
    String call(String tenant, String serviceCode, String endpointCode,
                Map<String, Object> params, Map<String, String> headers);
}
