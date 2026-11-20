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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体观测：跨用户的会话、逐轮指标与评分。
 *
 * <p>数据在对话过程中已经落库（agent_session / agent_message / agent_run /
 * agent_model_call），这里只做只读聚合。模型调用请求体由
 * {@link RecordingModel} 在 Model.stream 拦截点采集，
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
                                List<ModelCallRow> modelCalls,
                                Map<String, Object> mergedRequest) {
    }

    /**
     * 导出单元：一个会话的微调样本。
     *
     * <p>request 是合并后的完整请求体（OpenAI 风格：model / messages / tools /
     * 采样参数），与观测页 JSON 视图完全一致；messages[0] 是 skill 注入后的
     * system prompt，末尾追加了本轮最终 assistant 回复。tools 含 skill 自带
     * 工具；assistant 的 tool_calls 只有入参、tool 消息只有出参，没有平台内部结构。</p>
     */
    public record ExportedSession(ObserveSessionRow session,
                                  Map<String, Object> request) {
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
        List<AgentSessionService.MessageView> views = sessionService.messageViews(sessionId);
        return new SessionDetail(row, systemPrompt,
                views,
                runs.listBySession(sessionId),
                calls,
                mergeRequests(calls, views));
    }

    /**
     * 把「每次调用的完整请求体」压成一份可整体阅读的请求。
     *
     * <p>请求体是累积的：第 N 次调用天然包含前 N-1 次的全部 messages，
     * 逐份展示会大段重复。这里取 messages 最长的那一份作为完整对话，
     * tools 只保留最近一次非空定义，采样参数取最新一次，页面因此只需渲染一遍。</p>
     *
     * <p>最后一次回复是请求之后才生成的，不在任何请求体里，这里从平台落库的
     * 消息里补到末尾，使这份请求本身就是一次完整对话（观测视图与导出共用）。</p>
     */
    private Map<String, Object> mergeRequests(List<ModelCallRow> calls,
                                              List<AgentSessionService.MessageView> views) {
        Map<String, Object> widest = null;
        Map<String, Object> toolsSource = null;
        int widestSize = -1;
        for (ModelCallRow call : calls) {
            Map<String, Object> body = readRequest(call.requestJson());
            if (body == null) {
                continue;
            }
            int size = body.get("messages") instanceof List<?> list ? list.size() : 0;
            if (size >= widestSize) {
                widestSize = size;
                widest = body;
            }
            if (body.get("tools") instanceof List<?> tools && !tools.isEmpty()) {
                toolsSource = body;
            }
        }
        if (widest == null) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>(widest);
        if (toolsSource != null && toolsSource != widest) {
            merged.put("tools", toolsSource.get("tools"));
        }
        appendFinalAnswer(merged, views);
        return merged;
    }

    private void appendFinalAnswer(Map<String, Object> merged,
                                   List<AgentSessionService.MessageView> views) {
        AgentSessionService.MessageView last = null;
        for (int i = views.size() - 1; i >= 0; i--) {
            AgentSessionService.MessageView view = views.get(i);
            if ("ASSISTANT".equalsIgnoreCase(view.role())
                    && view.content() != null && !view.content().isBlank()) {
                last = view;
                break;
            }
        }
        if (last == null) {
            return;
        }
        List<Object> messages = merged.get("messages") instanceof List<?> list
                ? new ArrayList<>(list) : new ArrayList<>();
        if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof Map<?, ?> tail
                && "assistant".equals(String.valueOf(tail.get("role")))
                && last.content().equals(text(tail.get("content")))) {
            return;
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("role", "assistant");
        answer.put("content", last.content());
        messages.add(answer);
        merged.put("messages", messages);
    }

    private static String text(Object content) {
        if (content == null) {
            return "";
        }
        return content instanceof String value ? value : String.valueOf(content);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRequest(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return null;
        }
        try {
            return (Map<String, Object>) mapper.readValue(requestJson, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 导出：按筛选条件批量取会话，每会话附合并后的完整请求体（含最终回复）。 */
    public List<ExportedSession> exportSessions(String tenant, SessionFilter filter, int limit) {
        int size = Math.max(1, Math.min(limit <= 0 ? 200 : limit, MAX_EXPORT_LIMIT));
        List<ObserveSessionRow> rows = observe.listSessions(tenant, filter.agentId(),
                filter.userId(), filter.keyword(), size, 0).items();
        return rows.stream()
                .map(row -> new ExportedSession(row, requestOf(row.id())))
                .toList();
    }

    public ExportedSession exportSession(String tenant, long sessionId) {
        ObserveSessionRow row = observe.findSession(tenant, sessionId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "会话不存在"));
        return new ExportedSession(row, requestOf(sessionId));
    }

    private Map<String, Object> requestOf(long sessionId) {
        return mergeRequests(modelCalls.listBySession(sessionId),
                sessionService.messageViews(sessionId));
    }

    public List<AgentOption> agentOptions(String tenant) {
        return definitions.list(tenant).stream()
                .map(agent -> new AgentOption(agent.id(), agent.agentCode(), agent.name()))
                .toList();
    }
}
