package com.example.baseplate.platform.domain;

import java.util.Map;
import java.util.Set;

/** 第三方服务的一个端点：路径、方法与工具投影元数据。 */
public record ExternalEndpoint(
        String code,
        String path,
        String method,
        boolean readonly,
        String toolCode,
        String toolName,
        String toolDescription,
        Map<String, Object> paramsSchema,
        Set<String> pages,
        String permissionCode) {
}
