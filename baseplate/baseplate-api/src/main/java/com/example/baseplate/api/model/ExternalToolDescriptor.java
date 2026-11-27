package com.example.baseplate.api.model;

import java.util.Map;
import java.util.Set;

/**
 * 外部服务在 Agent 侧的工具投影。
 *
 * <p>平台负责注册服务、保管凭证、把端点投影成工具；Agent 只认工具，
 * 不感知「开放平台 / App / credential」等概念。</p>
 *
 * @param code           全局唯一工具编码
 * @param name           展示名
 * @param description    给模型看的工具说明
 * @param serviceCode    平台注册的服务编码，用于运行时解析凭证与地址
 * @param endpointCode   平台注册的端点编码
 * @param readonly       只读端点可以在服务端执行；否则必须是前端执行的外部工具
 * @param paramsSchema   入参 JSON Schema
 * @param pages          页面作用域；为空或含 * 表示全部
 * @param permissionCode 用户权限点
 */
public record ExternalToolDescriptor(
        String code,
        String name,
        String description,
        String serviceCode,
        String endpointCode,
        boolean readonly,
        Map<String, Object> paramsSchema,
        Set<String> pages,
        String permissionCode) {
}
