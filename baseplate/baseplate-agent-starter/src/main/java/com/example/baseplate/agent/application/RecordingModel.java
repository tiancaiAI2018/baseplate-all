package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentModelCallRepository;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>会话标识优先从本次调用的 Reactor Context 里取
 * {@link AgentBase#RUNTIME_CONTEXT_KEY}（即 {@link RuntimeContext} 的 sessionId），
 * 这是最可靠的通道：HITL 确认与外部工具结果恢复时，框架只从恢复消息的 metadata
 * 里取 ConfirmResult / ToolResult，并不会把恢复消息本身写进模型上下文，所以
 * 依赖 messages metadata 的关联会在这两条恢复路径上丢失，表现为「恢复后的模型
 * 调用没有观测记录」。{@code streamEvents(msgs, context)} 会把 RuntimeContext
 * 装进 Reactor Context，模型调用链内部始终可见。</p>
 *
 * <p>输入消息 metadata 里的会话标识保留为兜底通道（例如旧调用点或没有
 * RuntimeContext 的路径）；测试连接等 sessionId 不是数字的调用自动跳过。
 * 落库失败只静默跳过，绝不影响对话链路。</p>
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

    /** 会话归属：sessionId 一定是数字（agent_session 主键），tenant 缺省 default。 */
    private record SessionRef(long sessionId, String tenant) {
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        // deferContextual：订阅时才能读到上游 contextWrite 装进来的 RuntimeContext。
        // HITL / 外部工具恢复路径靠它拿到会话标识，不能退回只看 messages metadata。
        return Flux.deferContextual(contextView -> {
            recordAsync(messages, tools, options, contextView);
            Flux<ChatResponse> upstream = delegate.stream(messages, tools, options);
            TokenMeter meter = resolveMeter(contextView);
            if (meter == null) {
                return upstream;
            }
            // 流式响应的 usage 只在最后一个 chunk 出现（stream_options.include_usage），
            // 取最后一次而非逐 chunk 累加，避免个别厂商每个 chunk 都回传累计值。
            AtomicReference<ChatUsage> lastUsage = new AtomicReference<>();
            return upstream
                    .doOnNext(response -> {
                        if (response != null && response.getUsage() != null) {
                            lastUsage.set(response.getUsage());
                        }
                    })
                    .doOnComplete(() -> meter.add(lastUsage.get()));
        });
    }

    private TokenMeter resolveMeter(ContextView contextView) {
        RuntimeContext runtimeContext = contextView.getOrDefault(
                AgentBase.RUNTIME_CONTEXT_KEY, null);
        return runtimeContext == null ? null : runtimeContext.get(TokenMeter.CONTEXT_KEY);
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

    /** 只有会话归属解析留在订阅线程；序列化与落库全部丢到独立线程，不阻塞响应流。 */
    private void recordAsync(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options,
                            ContextView contextView) {
        try {
            SessionRef ref = resolveSession(messages, contextView);
            if (ref == null) {
                return;
            }
            long sessionId = ref.sessionId();
            int step = steps.computeIfAbsent(sessionId, key -> new AtomicInteger())
                    .incrementAndGet();
            if (steps.size() > 4096) {
                // 简单防膨胀：会话很多时整体清零，序号从头累计
                steps.clear();
            }
            String model = getModelName();
            String tenant = ref.tenant();
            executor.execute(() -> {
                try {
                    String json = mapper.writeValueAsString(buildRequestBody(messages, tools, options));
                    repository.record(tenant, sessionId, step, model, json);
                } catch (RuntimeException ignored) {
                    // 观测数据写失败不影响对话
                }
            });
        } catch (RuntimeException ignored) {
            // 会话标识缺失或格式异常，跳过观测
        }
    }

    /** 会话标识优先读 RuntimeContext；没有时回退输入消息 metadata。 */
    private SessionRef resolveSession(List<Msg> messages, ContextView contextView) {
        RuntimeContext runtimeContext = contextView.getOrDefault(
                AgentBase.RUNTIME_CONTEXT_KEY, null);
        if (runtimeContext != null) {
            Long sessionId = parseSessionId(runtimeContext.getSessionId());
            if (sessionId != null) {
                Object tenant = runtimeContext.get("tenantId");
                return new SessionRef(sessionId,
                        tenant == null || String.valueOf(tenant).isBlank()
                                ? "default" : String.valueOf(tenant));
            }
        }
        Long sessionId = parseSessionId(findMeta(messages, META_SESSION_ID));
        if (sessionId == null) {
            return null;
        }
        Object tenant = findMeta(messages, META_TENANT_ID);
        return new SessionRef(sessionId, tenant == null || String.valueOf(tenant).isBlank()
                ? "default" : String.valueOf(tenant));
    }

    private Long parseSessionId(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            // 测试连接等非会话场景（config-test-*）不是数字，直接跳过观测
            return null;
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
