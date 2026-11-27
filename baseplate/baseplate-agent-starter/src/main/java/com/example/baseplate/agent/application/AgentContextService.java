package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentSessionRepository;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.AgentSession;
import com.example.baseplate.agent.model.ModelProviderFactory;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.api.exception.BaseplateException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话上下文用量与手动压缩。
 *
 * <p>模型上下文由 agentscope 的 {@link AgentStateStore} 持有（key = {@code agent_state}），
 * 不是业务表 {@code agent_message}。压缩算法使用 AgentScope 官方 harness 的
 * {@link ConversationCompactor}（工具结果裁剪、参数截断、官方摘要 prompt、安全截断点），
 * 这里只负责加载状态、触发压缩、CAS 写回和清缓存。</p>
 *
 * <p>自动压缩在 {@code CompactionMiddleware} 里随每轮推理执行，不经过此类。</p>
 */
public final class AgentContextService {

    private static final Logger log = LoggerFactory.getLogger(AgentContextService.class);
    private static final String STATE_KEY = "agent_state";
    private static final Duration COMPACT_TIMEOUT = Duration.ofSeconds(90);

    private final AgentStateStore stateStore;
    private final AgentDefinitionRepository definitions;
    private final AgentSessionRepository sessions;
    private final ModelProviderFactory modelFactory;
    private final AgentRuntimeFactory runtimeFactory;
    private final AgentProperties properties;

    public AgentContextService(AgentStateStore stateStore,
                               AgentDefinitionRepository definitions,
                               AgentSessionRepository sessions,
                               ModelProviderFactory modelFactory,
                               AgentRuntimeFactory runtimeFactory,
                               AgentProperties properties) {
        this.stateStore = stateStore;
        this.definitions = definitions;
        this.sessions = sessions;
        this.modelFactory = modelFactory;
        this.runtimeFactory = runtimeFactory;
        this.properties = properties;
    }

    /** estimatedTokens 为当前模型上下文估算值，不是账单累计用量。 */
    public record ContextUsage(int estimatedTokens, int contextWindow, int percent, int messageCount) {
    }

    public record CompactResult(int beforeTokens, int afterTokens, int beforeMessages,
                                int afterMessages, String summary) {
    }

    public ContextUsage usage(String tenant, String userId, long sessionId) {
        AgentSession session = requireSession(tenant, userId, sessionId);
        AgentDefinition agent = requireAgent(tenant, session.agentId());
        AgentState state = loadState(storeUser(tenant, userId), sessionId);
        List<Msg> context = state == null ? List.of() : state.getContext();
        int window = AgentCompactionConfig.contextWindow(properties, agent);
        int tokens = TokenCounterUtil.calculateToken(context);
        return new ContextUsage(tokens, window, percent(tokens, window), context.size());
    }

    /** 手动压缩：官方 compactor 生成摘要 + 保留最近 N 条，写回 AgentState。 */
    public CompactResult compact(String tenant, String userId, long sessionId, int keepMessages) {
        AgentSession session = requireSession(tenant, userId, sessionId);
        AgentDefinition agent = requireAgent(tenant, session.agentId());
        String storeUser = storeUser(tenant, userId);
        VersionedState<AgentState> versioned = stateStore.getVersioned(
                storeUser, String.valueOf(sessionId), STATE_KEY, AgentState.class);
        AgentState state = versioned.value();
        if (state == null) {
            throw new BaseplateException("NOT_FOUND", "该会话还没有模型上下文，请先发送一条消息");
        }
        List<Msg> context = state.getContext();
        int beforeTokens = TokenCounterUtil.calculateToken(context);
        int keep = Math.max(2, Math.min(keepMessages, 200));
        if (context.size() <= keep + 2) {
            return new CompactResult(beforeTokens, beforeTokens, context.size(), context.size(),
                    "对话太短，无需压缩");
        }

        // 官方 compactor 只处理非 SYSTEM 消息，SYSTEM 由调用方保留
        List<Msg> system = context.stream().filter(m -> m.getRole() == MsgRole.SYSTEM).toList();
        List<Msg> conversation = context.stream().filter(m -> m.getRole() != MsgRole.SYSTEM).toList();
        CompactionConfig config = AgentCompactionConfig.manual(properties, keepMessages);
        Model model = modelFactory.build(agent);
        ConversationCompactor compactor = new ConversationCompactor(
                model, new MemoryFlushManager(null, model));
        Optional<List<Msg>> compacted = compactor.compactIfNeeded(
                        RuntimeContext.empty(), conversation, config,
                        agent.agentCode(), String.valueOf(sessionId))
                .block(COMPACT_TIMEOUT);

        if (compacted == null || compacted.isEmpty()) {
            return new CompactResult(beforeTokens, beforeTokens, context.size(), context.size(),
                    "对话太短，无需压缩");
        }
        List<Msg> newContext = new ArrayList<>(system);
        newContext.addAll(compacted.get());

        state.contextMutable().clear();
        state.contextMutable().addAll(newContext);
        long version = stateStore.saveIfVersion(storeUser, String.valueOf(sessionId), STATE_KEY,
                state, versioned.version());
        if (stateStore.supportsVersioning() && version == AgentStateStore.UNVERSIONED) {
            throw new BaseplateException("CONFLICT", "会话正在使用中，请稍后重试压缩");
        }
        // 清掉 runtime 实例，下一轮从库里加载压缩后的状态，避免内存 stateCache 覆盖
        runtimeFactory.invalidate(agent.id());
        int afterTokens = TokenCounterUtil.calculateToken(newContext);
        String summary = compacted.get().isEmpty() ? "" : compacted.get().get(0).getTextContent();
        log.info("会话上下文已压缩 sessionId={} messages={}->{} tokens={}->{}",
                sessionId, context.size(), newContext.size(), beforeTokens, afterTokens);
        return new CompactResult(beforeTokens, afterTokens, context.size(), newContext.size(),
                summary == null ? "" : summary);
    }

    private static int percent(int tokens, int window) {
        return Math.max(0, Math.min(100, (int) Math.round(tokens * 100.0 / window)));
    }

    private AgentSession requireSession(String tenant, String userId, long sessionId) {
        return sessions.findOwned(tenant, userId, sessionId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "会话不存在"));
    }

    private AgentDefinition requireAgent(String tenant, long agentId) {
        return definitions.find(tenant, agentId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
    }

    private AgentState loadState(String storeUser, long sessionId) {
        return stateStore.get(storeUser, String.valueOf(sessionId), STATE_KEY, AgentState.class)
                .orElse(null);
    }

    /** 与 AgentChatService 运行时一致：租户拼进 userId，避免不同租户同名用户共用上下文。 */
    private String storeUser(String tenant, String userId) {
        return tenant + ":" + userId;
    }
}
