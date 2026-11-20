package com.example.baseplate.agent.runtime;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.DefaultToolResultConverter;
import io.agentscope.core.tool.ToolResultConverter;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把工具结果里的 {@code ui} 指令从正文挪到 {@link ToolResultBlock#getMetadata()}。
 *
 * <p>formatter 只序列化 {@code ToolResultBlock.getOutput()}，metadata 不会进入模型上下文；
 * 因此模型只读业务字段，前端从 tool_result 事件的 metadata 取卡片数据，同一份 JSON 不再付两次 token。</p>
 */
public final class UiAwareToolResultConverter implements ToolResultConverter {

    public static final String UI_METADATA_KEY = "ui";

    private static final int MAX_UNWRAP = 3;

    private final ToolResultConverter fallback = new DefaultToolResultConverter();

    @Override
    public ToolResultBlock convert(Object result, Type returnType) {
        Map<?, ?> payload = asPayload(result);
        if (payload == null) {
            return fallback.convert(result, returnType);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        payload.forEach((key, value) -> body.put(String.valueOf(key), value));
        Object ui = body.remove(UI_METADATA_KEY);
        TextBlock text = TextBlock.builder().text(JsonUtils.getJsonCodec().toJson(body)).build();
        if (ui instanceof Map<?, ?> card) {
            return ToolResultBlock.of(text, Map.<String, Object>of(UI_METADATA_KEY, card));
        }
        return ToolResultBlock.of(List.of(text));
    }

    /**
     * 工具返回值可能是 Map，也可能是 JSON 字符串；SDK 还可能再套一层字符串，
     * 这里统一剥壳后再判断有没有 ui。
     */
    private Map<?, ?> asPayload(Object result) {
        if (result instanceof Map<?, ?> map) {
            return map;
        }
        if (!(result instanceof String raw)) {
            return null;
        }
        try {
            JsonCodec codec = JsonUtils.getJsonCodec();
            Object value = codec.fromJson(raw, Object.class);
            for (int depth = 0; depth < MAX_UNWRAP && value instanceof String text; depth++) {
                String trimmed = text.trim();
                if (!trimmed.startsWith("{")) {
                    return null;
                }
                value = codec.fromJson(trimmed, Object.class);
            }
            return value instanceof Map<?, ?> map ? map : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
