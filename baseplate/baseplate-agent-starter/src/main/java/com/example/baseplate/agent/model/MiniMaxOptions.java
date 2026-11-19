package com.example.baseplate.agent.model;

import io.agentscope.core.model.GenerateOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MiniMax 的请求参数装配（纯函数）。
 *
 * <p>MiniMax 的思考开关只能调深浅，且 {@code MiniMaxFormatter} 会清掉
 * {@code reasoning_effort}，所以必须走 {@code additionalBodyParams} 才能发出去；
 * 不设置时服务端默认按最深档思考，又慢又贵。</p>
 */
public final class MiniMaxOptions {

    private MiniMaxOptions() {
    }

    public static boolean isMiniMax(String modelName) {
        return modelName != null && modelName.toLowerCase(Locale.ROOT).startsWith("minimax");
    }

    public static GenerateOptions build(String modelName, String reasoningEffort,
                                        int maxOutputTokens, Double temperature) {
        GenerateOptions.Builder builder = GenerateOptions.builder()
                .maxCompletionTokens(maxOutputTokens);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (!isMiniMax(modelName)) {
            return builder.build();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            extra.put("reasoning_effort", reasoningEffort.trim().toLowerCase(Locale.ROOT));
        }
        // M3.1 的思考常开，传 disabled 会 400；只能声明自适应
        extra.put("thinking", Map.of("type", "adaptive"));
        return builder.additionalBodyParams(extra).build();
    }
}
