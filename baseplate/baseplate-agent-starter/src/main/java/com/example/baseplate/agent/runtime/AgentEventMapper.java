package com.example.baseplate.agent.runtime;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agentscope 事件 → 前端 SSE 事件的唯一映射点。
 *
 * <p>只映射稳定语义；厂商差异（reasoning_content / thinking block）已由 agentscope
 * 的 formatter 归一，这里不再解析文本标签。</p>
 */
public final class AgentEventMapper {

    public record Mapped(String type, Map<String, Object> data) {
    }

    private AgentEventMapper() {
    }

    public static Mapped map(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) {
            return delta("text", e.getDelta());
        }
        if (event instanceof ThinkingBlockDeltaEvent e) {
            return delta("thinking", e.getDelta());
        }
        if (event instanceof ToolCallStartEvent e) {
            String tool = e.getToolCallName() == null ? "" : e.getToolCallName();
            // 技能加载本质是工具调用，但对用户应显示为「使用技能」而不是普通工具
            if (tool.startsWith("load_skill")) {
                Map<String, Object> data = payload(e.getToolCallId(), tool, "start");
                data.put("name", tool);
                return new Mapped("skill", data);
            }
            return new Mapped("tool_call", payload(e.getToolCallId(), e.getToolCallName(), "start"));
        }
        if (event instanceof ToolCallEndEvent e) {
            return new Mapped("tool_call", payload(e.getToolCallId(), e.getToolCallName(), "end"));
        }
        if (event instanceof ToolResultStartEvent e) {
            return new Mapped("tool_result", payload(e.getToolCallId(), e.getToolCallName(), "start"));
        }
        if (event instanceof ToolResultTextDeltaEvent e) {
            Map<String, Object> data = payload(e.getToolCallId(), e.getToolCallName(), "delta");
            data.put("delta", e.getDelta() == null ? "" : e.getDelta());
            return new Mapped("tool_result", data);
        }
        if (event instanceof ToolResultEndEvent e) {
            // 外部工具暂停时框架会先发一条 suspended 结果；它不是失败，
            // 真正的执行结果由 external_tool_call 卡片展示，这里不能报红。
            if (e.getMetadata() != null
                    && e.getMetadata().containsKey(io.agentscope.core.message.ToolResultBlock.METADATA_SUSPENDED)) {
                return null;
            }
            Map<String, Object> data = payload(e.getToolCallId(), e.getToolCallName(), "end");
            ToolResultState state = e.getState();
            data.put("state", state == null ? "unknown" : state.getValue());
            data.put("ok", state == ToolResultState.SUCCESS);
            if (e.getMetadata() != null
                    && e.getMetadata().get(UiAwareToolResultConverter.UI_METADATA_KEY)
                    instanceof Map<?, ?> ui) {
                data.put(UiAwareToolResultConverter.UI_METADATA_KEY, ui);
            }
            return new Mapped("tool_result", data);
        }
        return null;
    }

    private static Mapped delta(String type, String delta) {
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("delta", delta);
        return new Mapped(type, data);
    }

    private static Map<String, Object> payload(String callId, String tool, String phase) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", callId == null ? "" : callId);
        data.put("tool", tool == null ? "" : tool);
        data.put("phase", phase);
        return data;
    }
}
