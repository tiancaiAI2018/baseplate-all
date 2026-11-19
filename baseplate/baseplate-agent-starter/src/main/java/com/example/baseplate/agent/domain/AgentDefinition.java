package com.example.baseplate.agent.domain;

import java.util.List;
import java.util.Map;

/**
 * 智能体定义（数据库行快照）。
 *
 * <p>{@code apiKeyEnc} 只保存 AES-GCM 密文；对外接口永远不返回该字段，
 * 页面只显示「已配置 / 未配置」。</p>
 */
public record AgentDefinition(
        long id,
        String tenantId,
        String agentCode,
        String name,
        String description,
        String provider,
        String modelName,
        String baseUrl,
        String apiKeyEnc,
        String systemPrompt,
        Map<String, Object> modelParams,
        List<String> allowedPages,
        String status,
        int revision) {

    public boolean enabled() {
        return "ENABLED".equalsIgnoreCase(status);
    }

    /**
     * 页面作用域判定：配置 {@code *} 表示所有页面；否则要求精确匹配。
     * 请求必须带 pageKey，后端不猜当前页面。
     */
    public boolean allowsPage(String pageKey) {
        if (pageKey == null || pageKey.isBlank()) {
            return false;
        }
        if (allowedPages == null || allowedPages.isEmpty()) {
            return false;
        }
        return allowedPages.contains("*") || allowedPages.contains(pageKey);
    }

    public boolean hasApiKey() {
        return apiKeyEnc != null && !apiKeyEnc.isBlank();
    }
}
