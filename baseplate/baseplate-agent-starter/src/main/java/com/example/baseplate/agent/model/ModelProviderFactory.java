package com.example.baseplate.agent.model;

import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.runtime.crypto.AesGcmCipher;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;

import java.util.Locale;
import java.util.Map;

/**
 * 按配置创建模型客户端。
 *
 * <p>「OpenAI 兼容」覆盖 MiniMax / DeepSeek / Moonshot / vLLM 等
 * {@code /v1/chat/completions} 端点；协议不兼容的厂商走各自原生扩展。
 * 新增厂商只需要在 switch 加一个分支，对话链路完全不动。</p>
 */
public final class ModelProviderFactory {

    private final AgentProperties properties;

    public ModelProviderFactory(AgentProperties properties) {
        this.properties = properties;
    }

    public Model build(AgentDefinition definition) {
        String provider = definition.provider() == null
                ? "OPENAI_COMPATIBLE" : definition.provider().toUpperCase(Locale.ROOT);
        String baseUrl = blankToDefault(definition.baseUrl(), properties.getDefaultBaseUrl());
        String apiKey = resolveApiKey(definition);
        int contextWindow = intParam(definition, "contextWindow", properties.getContextWindow());
        int maxOutputTokens = intParam(definition, "maxOutputTokens", properties.getMaxOutputTokens());
        Double temperature = doubleParam(definition, "temperature");
        String reasoningEffort = stringParam(definition, "reasoningEffort");

        return switch (provider) {
            case "DASHSCOPE" -> DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(definition.modelName())
                    .baseUrl(baseUrl)
                    .stream(true)
                    .build();
            case "ANTHROPIC" -> AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(definition.modelName())
                    .baseUrl(baseUrl)
                    .stream(true)
                    .build();
            case "GEMINI" -> GeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(definition.modelName())
                    .baseUrl(baseUrl)
                    .build();
            case "OLLAMA" -> OllamaChatModel.builder()
                    .modelName(definition.modelName())
                    .baseUrl(baseUrl)
                    .stream(true)
                    .build();
            default -> openAiCompatible(definition, apiKey, baseUrl, contextWindow,
                    maxOutputTokens, temperature, reasoningEffort);
        };
    }

    private Model openAiCompatible(AgentDefinition definition, String apiKey, String baseUrl,
                                   int contextWindow, int maxOutputTokens,
                                   Double temperature, String reasoningEffort) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(definition.modelName())
                .stream(true)
                .contextWindowSize(contextWindow)
                .generateOptions(MiniMaxOptions.build(definition.modelName(), reasoningEffort,
                        maxOutputTokens, temperature));
        if (MiniMaxOptions.isMiniMax(definition.modelName())) {
            // 让思考内容走独立字段，事件流里才能映射成 thinking，而不是混进正文
            builder.formatter(new MiniMaxFormatter());
        }
        return builder.build();
    }

    /** 密钥为空时回退到 .env 种子值，保证首次启动即可对话。 */
    private String resolveApiKey(AgentDefinition definition) {
        String stored = definition.apiKeyEnc();
        if (stored != null && !stored.isBlank()) {
            String plain = AesGcmCipher.decryptIfNeeded("agent.api-key", stored,
                    properties.getSecretKey());
            if (plain != null && !plain.isBlank()) {
                return plain;
            }
        }
        if (properties.getDefaultApiKey() != null && !properties.getDefaultApiKey().isBlank()) {
            return properties.getDefaultApiKey();
        }
        throw new IllegalStateException("智能体未配置 API Key，且环境变量 BASEPLATE_AGENT_API_KEY 为空");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stringParam(AgentDefinition definition, String key) {
        Object value = params(definition).get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intParam(AgentDefinition definition, String key, int fallback) {
        Object value = params(definition).get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double doubleParam(AgentDefinition definition, String key) {
        Object value = params(definition).get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> params(AgentDefinition definition) {
        return definition.modelParams() == null ? Map.of() : definition.modelParams();
    }
}
