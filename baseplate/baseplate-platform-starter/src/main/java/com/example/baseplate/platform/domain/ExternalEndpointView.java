package com.example.baseplate.platform.domain;

import java.util.List;
import java.util.Map;

/** 控制台端点详情。 */
public record ExternalEndpointView(
        String code,
        String path,
        String method,
        boolean readonly,
        String toolCode,
        String toolName,
        String toolDescription,
        Map<String, Object> paramsSchema,
        List<String> pages,
        String permissionCode,
        String status,
        int sortNo) {
}
