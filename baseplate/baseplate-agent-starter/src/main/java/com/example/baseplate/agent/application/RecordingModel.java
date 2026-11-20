package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentModelCallRepository;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型调用观测：装饰 agentscope 的 {@link Model}，在每次真正发出 HTTP 请求前
 * 把请求体（OpenAI chat/completions 风格）异步落库。
 *
 * <p>为什么用 Model 装饰器而不是 middleware 的 onModelCall：agentscope 2.0.4 的
 * ReActAgent 只接线了 onSystemPrompt，onModelCall 在接口上存在但执行链不调用；
 * 而 Model.stream 是 2.0.4 所有模型调用的必经点（CallExecution 与摘要两条路径都走它），
 * 在这里拿到的就是「实际发送给模型 HTTP 接口」的最准确快照：messages 含 skill
 * 注入后的 system 消息，tools 含全部下发工具（自定义 + skill 自带）。</p>
 *
 * <p>会话标识通过输入消息的 metadata 携带（见 AgentChatService 的 trace metadata）；
 * 测试连接等没有 metadata 的调用自动跳过。落库失败只静默跳过，绝不影响对话链路。</p>
 */
public final class RecordingModel implements Model {

    /** 输入消息 metadata 里携带的会话 id / 租户 key（AgentChatService 写入）。 */
    public static final String META_SESSION_ID = "baseplate.sessionId";
    public static final String META_TENANT_ID = "baseplate.tenantId";

    private final Model delegate;
    private final AgentModelCallRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 会话 → 调用序号；step 语义为该会话内第 N 次模型调用（累计）。 */
    private final ConcurrentHashMap<Long, AtomicInteger> steps = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "agent-model-call-recorder");
        thread.setDaemon(true);
        return thread;
    });

    public RecordingModel(Model delegate, AgentModelCallRepository repository) {
        this.delegate = delegate;
        this.repository = repository;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        recordAsync(messages, tools, options);
        return delegate.stream(messages, tools, options);
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    /** 序列化与落库都在独立线程：不阻塞响应流，写库失败也不打扰对话。 */
    private void recordAsync(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        try {
            Long sessionId = findMeta(messages, META_SESSION_ID) == null
                    ? null : Long.valueOf(String.valueOf(findMeta(messages, META_SESSION_ID)));
            if (sessionId == null) {
                // 测试连接等没有会话标识的调用，跳过观测
                return;
            }
            String tenant = String.valueOf(findMeta(messages, META_TENANT_ID));
            int step = steps.computeIfAbsent(sessionId, key -> new AtomicInteger())
                    .incrementAndGet();
            if (steps.size() > 4096) {
                // 简单防膨胀：会话很多时整体清零，序号从头累计
                steps.clear();
            }
            Map<String, Object> request = buildRequestBody(messages, tools, options);
            String json = mapper.writeValueAsString(request);
            String model = getModelName();
            executor.execute(() -> {
                try {
                    repository.record(tenant, sessionId, step, model, json);
                } catch (RuntimeException ignored) {
                    // 观测数据写失败不影响对话
                }
            });
        } catch (RuntimeException ignored) {
            // metadata 缺失或格式异常，跳过观测
        }
    }

    /** 从输入消息的 metadata 里找会话标识：第一条带该 key 的消息即视为来源。 */
    private Object findMeta(List<Msg> messages, String key) {
        if (messages == null) {
            return null;
        }
        for (Msg msg : messages) {
            Map<String, Object> metadata = msg.getMetadata();
            if (metadata != null && metadata.containsKey(key)) {
                return metadata.get(key);
            }
        }
        return null;
    }

    /**
     * 组装 OpenAI chat/completions 风格请求体。
     *
     * <p>只保留通用 HTTP 接口字段：messages / tools / 模型采样参数。
     * assistant 消息的 tool_calls 只有 name + arguments（入参），tool 消息只有
     * tool_call_id + content（出参），不夹带平台内部结构。</p>
     */
    private Map<String, Object> buildRequestBody(List<Msg> messages, List<ToolSchema> tools,
                                                 GenerateOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", toOpenAiMessages(messages));
        List<Map<String, Object>> openAiTools = toOpenAiTools(tools);
        if (!openAiTools.isEmpty()) {
            body.put("tools", openAiTools);
        }
        if (options != null) {
            putIfNotNull(body, "model", options.getModelName());
            putIfNotNull(body, "stream", options.getStream());
            putIfNotNull(body, "temperature", options.getTemperature());
            putIfNotNull(body, "top_p", options.getTopP());
            putIfNotNull(body, "max_tokens", options.getMaxTokens());
            putIfNotNull(body, "max_completion_tokens", options.getMaxCompletionTokens());
            putIfNotNull(body, "frequency_penalty", options.getFrequencyPenalty());
            putIfNotNull(body, "presence_penalty", options.getPresencePenalty());
            putIfNotNull(body, "reasoning_effort", options.getReasoningEffort());
            putIfNotNull(body, "top_k", options.getTopK());
            putIfNotNull(body, "seed", options.getSeed());
            putIfNotNull(body, "parallel_tool_calls", options.getParallelToolCalls());
            putIfNotNull(body, "tool_choice", options.getToolChoice());
            Map<String, Object> extra = options.getAdditionalBodyParams();
            if (extra != null) {
                body.putAll(extra);
            }
        }
        if (!body.containsKey("model")) {
            body.put("model", getModelName());
        }
        return body;
    }

    private List<Map<String, Object>> toOpenAiMessages(List<Msg> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (Msg msg : messages) {
            MsgRole role = msg.getRole();
            if (role == MsgRole.ASSISTANT) {
                result.add(assistantMessage(msg));
            } else if (role == MsgRole.TOOL) {
                toolResultMessages(msg, result);
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role == MsgRole.SYSTEM ? "system" : "user");
                item.put("content", textOf(msg.getContent()));
                result.add(item);
            }
        }
        return result;
    }

    /** assistant 消息：正文 + 推理内容（若厂商回传）+ tool_calls（仅 name/arguments）。 */
    private Map<String, Object> assistantMessage(Msg msg) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (ContentBlock block : blocks(msg)) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                thinking.append(thinkingBlock.getThinking());
            } else if (block instanceof ToolUseBlock toolUse) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", toolUse.getId());
                call.put("type", "function");
                call.put("function", Map.of(
                        "name", toolUse.getName(),
                        // OpenAI 协议里 arguments 是 JSON 字符串
                        "arguments", toJson(toolUse.getInput())));
                toolCalls.add(call);
            }
        }
        item.put("content", text.toString());
        if (!thinking.isEmpty()) {
            item.put("reasoning_content", thinking.toString());
        }
        if (!toolCalls.isEmpty()) {
            item.put("tool_calls", toolCalls);
        }
        return item;
    }

    /** tool 消息：tool_call_id + 出参文本，其余全部丢弃。 */
    private void toolResultMessages(Msg msg, List<Map<String, Object>> result) {
        for (ContentBlock block : blocks(msg)) {
            if (block instanceof ToolResultBlock toolResult) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", "tool");
                item.put("tool_call_id", toolResult.getId());
                item.put("content", textOf(toolResult.getOutput()));
                result.add(item);
            }
        }
    }

    private List<Map<String, Object>> toOpenAiTools(List<ToolSchema> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (tools == null) {
            return result;
        }
        for (ToolSchema schema : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", schema.getName());
            function.put("description", schema.getDescription());
            function.put("parameters", schema.getParameters() == null
                    ? Map.of("type", "object", "properties", Map.of()) : schema.getParameters());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function");
            item.put("function", function);
            result.add(item);
        }
        return result;
    }

    private List<ContentBlock> blocks(Msg msg) {
        return msg.getContent() == null ? List.of() : msg.getContent();
    }

    private String textOf(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        if (blocks != null) {
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                }
            }
        }
        return text.toString();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    private static void putIfNotNull(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }
}
