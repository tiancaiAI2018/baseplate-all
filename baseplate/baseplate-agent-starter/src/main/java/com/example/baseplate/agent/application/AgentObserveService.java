package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentModelCallRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentModelCallRepository.ModelCallRow;
import com.example.baseplate.agent.adapter.out.persistence.AgentObserveRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentObserveRepository.ObserveSessionRow;
import com.example.baseplate.agent.adapter.out.persistence.AgentRunRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentRunRepository.RunRow;
import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.api.exception.BaseplateException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 智能体观测：跨用户的会话、逐轮指标与评分。
 *
 * <p>数据在对话过程中已经落库（agent_session / agent_message / agent_run /
 * agent_model_call），这里只做只读聚合。模型调用请求体由
 * {@link ModelCallObservabilityMiddleware} 在 onModelCall 拦截点采集，
 * 是实际发送给模型 HTTP 接口的最准确快照。</p>
 */
public final class AgentObserveService {

    private static final int MAX_LIMIT = 200;
    private static final int MAX_EXPORT_LIMIT = 500;

    private final AgentObserveRepository observe;
    private final AgentDefinitionRepository definitions;
    private final AgentSessionService sessionService;
    private final AgentRunRepository runs;
    private final AgentModelCallRepository modelCalls;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public AgentObserveService(AgentObserveRepository observe,
                               AgentDefinitionRepository definitions,
                               AgentSessionService sessionService,
                               AgentRunRepository runs,
                               AgentModelCallRepository modelCalls) {
        this.observe = observe;
        this.definitions = definitions;
        this.sessionService = sessionService;
        this.runs = runs;
        this.modelCalls = modelCalls;
    }

    /** 会话列表的过滤条件，全部可选。 */
    public record SessionFilter(Long agentId, String userId, String keyword) {
    }

    /** 分页结果。 */
    public record PagedSessions(long total, List<ObserveSessionRow> items) {
    }

    /** 会话详情：会话行 + 实际发送的 system prompt + 完整消息回放 + 指标 + 模型调用请求。 */
    public record SessionDetail(ObserveSessionRow session,
                                String systemPrompt,
                                List<AgentSessionService.MessageView> messages,
                                List<RunRow> runs,
                                List<ModelCallRow> modelCalls) {
    }

    /**
     * 导出单元：一个会话的微调样本。
     *
     * <p>calls 里每项就是一次发给模型 HTTP 接口的完整请求体（OpenAI 风格：
     * model / messages / tools / 采样参数），messages[0] 是 skill 注入后的
     * system prompt，tools 含 skill 自带工具；assistant 的 tool_calls 只有
     * 入参、tool 消息只有出参，没有平台内部结构。</p>
     */
    public record ExportedSession(ObserveSessionRow session,
                                  List<Map<String, Object>> calls) {
    }

    /** 过滤下拉用的精简 Agent 选项，不暴露模型地址与密钥。 */
    public record AgentOption(long id, String agentCode, String name) {
    }

    public PagedSessions listSessions(String tenant, SessionFilter filter, int page, int limit) {
        int size = Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIMIT));
        int safePage = Math.max(1, page);
        return toPaged(observe.listSessions(tenant, filter.agentId(), filter.userId(),
                filter.keyword(), size, (long) (safePage - 1) * size));
    }

    private PagedSessions toPaged(AgentObserveRepository.PagedSessions paged) {
        return new PagedSessions(paged.total(), paged.items());
    }

    public SessionDetail sessionDetail(String tenant, long sessionId) {
        ObserveSessionRow row = observe.findSession(tenant, sessionId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "会话不存在"));
        List<ModelCallRow> calls = modelCalls.listBySession(sessionId);
        // system prompt 取最近一次实际请求的 messages[0]：skill 正文动态注入，
        // 只有请求体里的才是真正发出去的。没有记录时回退配置组装。
        String systemPrompt = calls.isEmpty()
                ? "" : modelCalls.latestSystemPrompt(sessionId).orElse("");
        return new SessionDetail(row, systemPrompt,
                sessionService.messageViews(sessionId),
                runs.listBySession(sessionId),
                calls);
    }

    /** 导出：按筛选条件批量取会话，每会话附全部模型调用请求体（原样）。 */
    public List<ExportedSession> exportSessions(String tenant, SessionFilter filter, int limit) {
        int size = Math.max(1, Math.min(limit <= 0 ? 200 : limit, MAX_EXPORT_LIMIT));
        List<ObserveSessionRow> rows = observe.listSessions(tenant, filter.agentId(),
                filter.userId(), filter.keyword(), size, 0).items();
        return rows.stream()
                .map(row -> new ExportedSession(row, requestsOf(row.id())))
                .toList();
    }

    public ExportedSession exportSession(String tenant, long sessionId) {
        ObserveSessionRow row = observe.findSession(tenant, sessionId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "会话不存在"));
        return new ExportedSession(row, requestsOf(sessionId));
    }

    /** 把落库的请求体 JSON 反序列化回 Map，导出时保持字段原样。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requestsOf(long sessionId) {
        return modelCalls.listBySession(sessionId).stream()
                .map(call -> {
                    try {
                        return (Map<String, Object>) mapper.readValue(call.requestJson(), Map.class);
                    } catch (RuntimeException e) {
                        return Map.<String, Object>of("raw", call.requestJson());
                    }
                })
                .toList();
    }

    public List<AgentOption> agentOptions(String tenant) {
        return definitions.list(tenant).stream()
                .map(agent -> new AgentOption(agent.id(), agent.agentCode(), agent.name()))
                .toList();
    }
}
