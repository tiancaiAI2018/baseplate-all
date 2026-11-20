package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentExternalCallRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentExternalCallRepository.ExternalCallRow;
import com.example.baseplate.agent.adapter.out.persistence.AgentHitlRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentHitlRepository.HitlRow;
import com.example.baseplate.agent.adapter.out.persistence.AgentHitlRepository.PendingCall;
import com.example.baseplate.agent.adapter.out.persistence.AgentMessageRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentRunRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentSessionRepository;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.AgentSession;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.runtime.AgentEventMapper;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.agent.runtime.SseEventWriter;
import com.example.baseplate.agent.tool.ToolDescriptor;
import com.example.baseplate.agent.tool.ToolRegistry;
import com.example.baseplate.api.exception.BaseplateException;
import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Exceptions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

/**
 * 对话编排：解析 Agent/会话 → 订阅 agentscope 事件流 → 映射 SSE → 落库。
 *
 * <p>HITL 走「暂停 + 恢复」：模型触发需确认工具时，事件流结束、审批单落库；
 * 用户决策后带 ConfirmResult 元数据再次调用同一个 Agent（上下文由 AgentStateStore 恢复）。</p>
 */
public final class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private static final int HEARTBEAT_SECONDS = 10;

    private final AgentDefinitionRepository definitions;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentRunRepository runs;
    private final AgentHitlRepository hitlRepository;
    private final AgentExternalCallRepository externalCallRepository;
    private final AgentRuntimeFactory runtimeFactory;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2,
            runnable -> {
                Thread thread = new Thread(runnable, "agent-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public AgentChatService(AgentDefinitionRepository definitions,
                            AgentSessionRepository sessions,
                            AgentMessageRepository messages,
                            AgentRunRepository runs,
                            AgentHitlRepository hitlRepository,
                            AgentExternalCallRepository externalCallRepository,
                            AgentRuntimeFactory runtimeFactory,
                            ToolRegistry toolRegistry,
                            AgentProperties properties) {
        this.definitions = definitions;
        this.sessions = sessions;
        this.messages = messages;
        this.runs = runs;
        this.hitlRepository = hitlRepository;
        this.externalCallRepository = externalCallRepository;
        this.runtimeFactory = runtimeFactory;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    public record ChatCommand(String agentCode, Long sessionId, String pageKey, String message) {
    }

    private record ToolSelection(Set<String> enabled, Map<String, ApprovalMode> policies) {
    }

    // ==================== 普通对话 ====================

    public SseEmitter stream(String tenant, CurrentUser user, ChatCommand command) {
        if (command.message() == null || command.message().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (command.pageKey() == null || command.pageKey().isBlank()) {
            throw new IllegalArgumentException("缺少 pageKey：页面作用域必须由前端显式传入");
        }

        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutSeconds() * 1000L);
        SseEventWriter writer = new SseEventWriter(emitter);

        AgentDefinition agent;
        AgentSession session;
        try {
            agent = resolveAgent(tenant, command.agentCode(), command.pageKey());
            session = resolveSession(tenant, user.userId(), agent, command);
            messages.append(tenant, session.id(), "USER", command.message(),
                    userBlocks(command.message()), null, 0);
            writer.send("session", Map.of(
                    "sessionId", session.id(),
                    "agentCode", agent.agentCode(),
                    "agentName", agent.name()));
        } catch (RuntimeException e) {
            log.warn("智能体会话初始化失败 tenant={} user={}: {}", tenant, user.userId(), e.toString());
            writer.send("error", Map.of("code", "AGENT_INIT_FAILED", "message", safeMessage(e)));
            writer.complete();
            return emitter;
        }

        try {
            ToolSelection selection = selectTools(tenant, agent, command.pageKey(), user);
            ReActAgent runtime = runtimeFactory.get(tenant, agent,
                    selection.enabled(), selection.policies(), skillNames(tenant, agent));
            RuntimeContext context = context(tenant, user, session, command.pageKey());
            syncPermissionPolicy(runtime, context, selection.policies());
            runTurn(tenant, user, session, agent, emitter, writer, runtime, context,
                    List.of(new UserMessage(command.message())), command.message(),
                    new ArrayList<>(), new StringBuilder(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("智能体启动失败 sessionId={}: {}", session.id(), e.toString());
            writer.send("error", Map.of("code", "AGENT_START_FAILED", "message", safeMessage(e)));
            writer.complete();
        }
        return emitter;
    }

    // ==================== HITL 恢复 ====================

    public SseEmitter resumeHitl(String tenant, CurrentUser user, long approvalId,
                                 boolean approved, String comment) {
        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutSeconds() * 1000L);
        SseEventWriter writer = new SseEventWriter(emitter);

        HitlRow row = hitlRepository.findPending(tenant, user.userId(), approvalId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "审批单不存在、已处理或已过期"));
        AgentSession session = sessions.findOwned(tenant, user.userId(), row.sessionId())
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "会话不存在"));
        AgentDefinition agent = definitions.find(tenant, row.agentId())
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
        String pageKey = session.pageKey() == null ? "unknown" : session.pageKey();
        if (!agent.enabled() || !agent.allowsPage(pageKey)) {
            throw new ForbiddenException("智能体已停用或不允许在当前页面使用");
        }
        // 先落决策再开流：并发点两次只有一次能成功，避免两路恢复同时改模型上下文
        boolean decided = hitlRepository.markDecided(approvalId,
                approved ? "APPROVED" : "REJECTED", comment,
                user.userId());
        if (!decided) {
            throw new BaseplateException("CONFLICT", "该审批已处理，请刷新后重试");
        }

        List<ConfirmResult> confirmResults = row.toolCalls().stream()
                .map(call -> new ConfirmResult(approved, resumeToolCall(call),
                        null, approved ? null : (comment == null || comment.isBlank()
                        ? "用户拒绝了该操作" : comment)))
                .toList();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        Msg resumeMessage = Msg.builder()
                .name(user.userId())
                .role(MsgRole.USER)
                .textContent(approved ? "用户已同意执行上述工具调用。" : "用户拒绝执行上述工具调用。")
                .metadata(metadata)
                .build();

        try {
            writer.send("hitl_resolved", Map.of(
                    "approvalId", approvalId, "approved", approved));
            writer.send("session", Map.of(
                    "sessionId", session.id(),
                    "agentCode", agent.agentCode(),
                    "agentName", agent.name()));
            ToolSelection selection = selectTools(tenant, agent, pageKey, user);
            ReActAgent runtime = runtimeFactory.get(tenant, agent,
                    selection.enabled(), selection.policies(), skillNames(tenant, agent));
            RuntimeContext context = context(tenant, user, session, pageKey);
            syncPermissionPolicy(runtime, context, selection.policies());
            String title = session.title() == null || session.title().isBlank()
                    ? "继续对话" : session.title();
            runTurn(tenant, user, session, agent, emitter, writer, runtime, context,
                    List.of(resumeMessage), title,
                    new ArrayList<>(), new StringBuilder(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("HITL 恢复失败 approvalId={}: {}", approvalId, e.toString());
            writer.send("error", Map.of("code", "AGENT_START_FAILED", "message", safeMessage(e)));
            writer.complete();
        }
        return emitter;
    }

    public List<Map<String, Object>> pendingHitl(String tenant, CurrentUser user, long sessionId) {
        return hitlRepository.listPending(tenant, user.userId(), sessionId).stream()
                .map(row -> Map.<String, Object>of(
                        "approvalId", row.id(),
                        "sessionId", row.sessionId(),
                        "replyId", row.replyId() == null ? "" : row.replyId(),
                        "expiresAt", row.expiresAt().toString(),
                        "toolCalls", row.toolCalls().stream().map(call -> Map.of(
                                "callId", call.callId(),
                                "tool", call.tool(),
                                "args", call.args())).toList()))
                .toList();
    }

    // ==================== 流式执行 ====================

    private void runTurn(String tenant, CurrentUser user, AgentSession session, AgentDefinition agent,
                         SseEmitter emitter, SseEventWriter writer, ReActAgent runtime,
                         RuntimeContext context, List<Msg> input, String title,
                         List<Map<String, Object>> blocks, StringBuilder answer, long startedAt) {
        long[] firstTokenAt = {-1L};
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Long> approvalRef = new AtomicReference<>();
        AtomicReference<String> externalReplyRef = new AtomicReference<>();
        StringBuilder thinking = new StringBuilder();
        Map<String, StringBuilder> toolTextBuffers = new HashMap<>();

        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                writer.heartbeat();
            } catch (RuntimeException ignored) {
                // 客户端断开由 onCompletion 统一收尾
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Disposable disposable = runtime.streamEvents(input, context).subscribe(
                event -> {
                    if (event instanceof RequireUserConfirmEvent confirm) {
                        long approvalId = createHitl(tenant, user, session, agent, confirm, blocks);
                        approvalRef.set(approvalId);
                        writer.send("hitl", hitlPayload(approvalId, confirm));
                        return;
                    }
                    if (event instanceof RequireExternalExecutionEvent external) {
                        String replyId = external.getReplyId() == null ? "" : external.getReplyId();
                        externalReplyRef.set(replyId);
                        writer.send("external_tool_call",
                                createExternalCalls(tenant, user, session, agent, external, blocks));
                        return;
                    }
                    AgentEventMapper.Mapped mapped = AgentEventMapper.map(event);
                    if (mapped == null) {
                        return;
                    }
                    // 外部工具的暂停结果不是失败，UI 由 external_tool_call 卡片负责
                    if ("tool_result".equals(mapped.type())
                            && "end".equals(String.valueOf(mapped.data().get("phase")))
                            && isExternalTool(tenant,
                            String.valueOf(mapped.data().getOrDefault("tool", "")))) {
                        return;
                    }
                    if (firstTokenAt[0] < 0 && ("text".equals(mapped.type())
                            || "thinking".equals(mapped.type()))) {
                        firstTokenAt[0] = System.currentTimeMillis();
                    }
                    // 工具结果里可能带 UI 指令（例如打开流程设计器），解析后单独推一帧
                    trackToolUi(mapped, toolTextBuffers, writer, blocks);
                    collect(mapped, blocks, answer, thinking);
                    writer.send(mapped.type(), mapped.data());
                },
                error -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    heartbeat.cancel(false);
                    if (Exceptions.unwrap(error) instanceof SseEventWriter.ClientDisconnectedException) {
                        log.info("客户端断开，取消智能体流 sessionId={}", session.id());
                        return;
                    }
                    log.warn("智能体流失败 sessionId={}: {}", session.id(), error.toString());
                    try {
                        writer.send("error", Map.of(
                                "code", "AGENT_STREAM_ERROR",
                                "message", "模型调用失败，请稍后重试"));
                    } catch (RuntimeException ignored) {
                        // 客户端可能已断开
                    }
                    persistAssistant(tenant, session, agent, answer, blocks,
                            startedAt, firstTokenAt[0], "ERROR", error);
                    sessions.touch(session.id(), 2, titleOf(title));
                    writer.complete();
                },
                () -> {
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    heartbeat.cancel(false);
                    long latency = System.currentTimeMillis() - startedAt;
                    boolean paused = approvalRef.get() != null || externalReplyRef.get() != null;
                    long messageId = persistAssistant(tenant, session, agent, answer, blocks,
                            startedAt, firstTokenAt[0], paused ? "PAUSED" : "OK", null);
                    sessions.touch(session.id(), 2, titleOf(title));
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("sessionId", session.id());
                    done.put("messageId", messageId);
                    done.put("latencyMs", latency);
                    done.put("ttftMs", firstTokenAt[0] < 0 ? 0 : firstTokenAt[0] - startedAt);
                    done.put("paused", paused);
                    if (paused) {
                        done.put("approvalId", approvalRef.get());
                    }
                    try {
                        writer.send("done", done);
                    } catch (RuntimeException ignored) {
                        // 客户端可能已断开
                    }
                    log.info("SSE 对话结束 sessionId={} paused={}", session.id(), paused);
                    writer.complete();
                });

        emitter.onCompletion(() -> {
            // 这里不能 dispose：complete() 会同步触发回调，
            // 在订阅的终止回调里 dispose 自己会让 Tomcat 来不及写完整的 chunked 结束块。
            heartbeat.cancel(false);
        });
        emitter.onTimeout(() -> {
            heartbeat.cancel(false);
            try {
                disposable.dispose();
            } catch (RuntimeException ignored) {
                // 同上
            }
            writer.complete();
        });
    }

    private long createHitl(String tenant, CurrentUser user, AgentSession session,
                            AgentDefinition agent, RequireUserConfirmEvent confirm,
                            List<Map<String, Object>> blocks) {
        List<PendingCall> calls = confirm.getToolCalls().stream()
                .map(tool -> new PendingCall(
                        tool.getId() == null ? "" : tool.getId(),
                        tool.getName() == null ? "" : tool.getName(),
                        tool.getInput() == null ? Map.of() : tool.getInput()))
                .toList();
        OffsetDateTime expiresAt = OffsetDateTime.now()
                .plusSeconds(properties.getHitlTimeoutSeconds());
        long approvalId = hitlRepository.create(tenant, session.id(), user.userId(),
                agent.id(), confirm.getReplyId(), calls, expiresAt);

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "hitl");
        block.put("approvalId", approvalId);
        block.put("status", "PENDING");
        block.put("toolCalls", calls.stream().map(call -> Map.of(
                "callId", call.callId(), "tool", call.tool(), "args", call.args())).toList());
        blocks.add(block);
        return approvalId;
    }

    private Map<String, Object> hitlPayload(long approvalId, RequireUserConfirmEvent confirm) {
        List<Map<String, Object>> calls = confirm.getToolCalls().stream().map(tool -> Map.of(
                "callId", tool.getId() == null ? "" : tool.getId(),
                "tool", tool.getName() == null ? "" : tool.getName(),
                "args", tool.getInput() == null ? Map.of() : tool.getInput())).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("replyId", confirm.getReplyId() == null ? "" : confirm.getReplyId());
        payload.put("toolCalls", calls);
        payload.put("expiresAt", OffsetDateTime.now()
                .plusSeconds(properties.getHitlTimeoutSeconds()).toString());
        return payload;
    }

    /**
     * 重建审批单里的工具调用。
     *
     * <p>审批参数落库时只保存了 input map，但 agentscope 执行前用
     * {@link ToolUseBlock#getContent()}（原始 JSON 串）做 JSON Schema 校验。
     * 如果这里不回填 content，确认后每个工具都会报
     * {@code argument "content" is null}，而不是真正执行。</p>
     */
    private ToolUseBlock resumeToolCall(PendingCall call) {
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        return new ToolUseBlock(call.callId(), call.tool(), args,
                mapper.writeValueAsString(args), null, ToolCallState.ASKING);
    }

    /**
     * 把本轮计算出的审批策略同步到会话级权限上下文。
     *
     * <p>PermissionContext 会随 AgentState 持久化，创建会话时写入一次；
     * 之后在配置页调整策略（例如关掉某个工具的「需人工确认」）时，
     * 老会话仍会读到旧规则。审批策略是每轮从数据库重新计算的配置，
     * 因此每轮开始前对齐一次，保证配置立即生效。</p>
     */
    private void syncPermissionPolicy(ReActAgent runtime, RuntimeContext context,
                                      Map<String, ApprovalMode> policies) {
        PermissionContextState desired = AgentRuntimeFactory.permissionContext(policies);
        String userId = context.getUserId();
        String sessionId = context.getSessionId();
        PermissionContextState current =
                runtime.getAgentState(userId, sessionId).getPermissionContext();
        if (!desired.equals(current)) {
            runtime.replacePermissionContext(userId, sessionId, desired);
        }
    }

    // ==================== 外部工具（前端执行） ====================

    private Map<String, Object> createExternalCalls(String tenant, CurrentUser user,
                                                    AgentSession session, AgentDefinition agent,
                                                    RequireExternalExecutionEvent event,
                                                    List<Map<String, Object>> blocks) {
        OffsetDateTime expiresAt = OffsetDateTime.now()
                .plusSeconds(properties.getHitlTimeoutSeconds());
        String replyId = event.getReplyId() == null ? "" : event.getReplyId();
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolUseBlock tool : event.getToolCalls()) {
            String callId = tool.getId() == null ? "" : tool.getId();
            String toolCode = tool.getName() == null ? "" : tool.getName();
            Map<String, Object> args = tool.getInput() == null ? Map.of() : tool.getInput();
            externalCallRepository.create(tenant, session.id(), user.userId(), agent.id(),
                    replyId, callId, toolCode, mapper.writeValueAsString(args), expiresAt);

            Map<String, Object> call = new LinkedHashMap<>();
            call.put("callId", callId);
            call.put("tool", toolCode);
            call.put("args", args);
            calls.add(call);

            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "external_tool_call");
            block.put("callId", callId);
            block.put("tool", toolCode);
            block.put("args", args);
            block.put("status", "PENDING");
            blocks.add(block);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("replyId", replyId);
        payload.put("calls", calls);
        payload.put("expiresAt", expiresAt.toString());
        return payload;
    }

    /**
     * 前端执行完外部工具后回传结果。多个 call 同属一个 replyId 时，
     * 只有最后一个结果到达才恢复模型；否则先回 {@code external_wait} 收尾。
     */
    public SseEmitter resumeExternal(String tenant, CurrentUser user, String callId,
                                     boolean ok, Object result, String error) {
        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutSeconds() * 1000L);
        SseEventWriter writer = new SseEventWriter(emitter);

        ExternalCallRow pending = externalCallRepository.findPending(tenant, user.userId(), callId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "外部调用不存在、已处理或已过期"));
        AgentSession session = sessions.findOwned(tenant, user.userId(), pending.sessionId())
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "会话不存在"));
        AgentDefinition agent = definitions.find(tenant, session.agentId())
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
        String pageKey = session.pageKey() == null ? "unknown" : session.pageKey();
        if (!agent.enabled() || !agent.allowsPage(pageKey)) {
            throw new ForbiddenException("智能体已停用或不允许在当前页面使用");
        }

        String resultJson = result == null ? "{}" : mapper.writeValueAsString(result);
        externalCallRepository.complete(tenant, user.userId(), callId, ok, resultJson, error);

        List<ExternalCallRow> calls = externalCallRepository.listByReply(session.id(), pending.replyId());
        List<ExternalCallRow> stillPending = calls.stream()
                .filter(item -> "PENDING".equals(item.status())).toList();
        if (!stillPending.isEmpty()) {
            writer.send("external_wait", Map.of(
                    "callId", callId, "remaining", stillPending.size()));
            writer.send("done", Map.of("sessionId", session.id(), "paused", true));
            writer.complete();
            return emitter;
        }

        List<ContentBlock> results = calls.stream().map(item -> (ContentBlock) ToolResultBlock.of(
                item.callId(), item.toolCode(),
                List.of(TextBlock.builder().text(resultText(item)).build()))).toList();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID,
                pending.replyId() == null ? "" : pending.replyId());
        Msg resumeMessage = Msg.builder()
                .name(user.userId())
                // 携带 ToolResultBlock 的恢复消息必须是 TOOL 角色；
                // USER 角色只允许 text/data/image/audio/video，框架会直接拒绝。
                .role(MsgRole.TOOL)
                .content(results)
                .metadata(metadata)
                .build();

        try {
            writer.send("external_result", Map.of("callId", callId, "ok", ok));
            writer.send("session", Map.of(
                    "sessionId", session.id(),
                    "agentCode", agent.agentCode(),
                    "agentName", agent.name()));
            ToolSelection selection = selectTools(tenant, agent, pageKey, user);
            ReActAgent runtime = runtimeFactory.get(tenant, agent,
                    selection.enabled(), selection.policies(), skillNames(tenant, agent));
            RuntimeContext context = context(tenant, user, session, pageKey);
            syncPermissionPolicy(runtime, context, selection.policies());
            // 外部工具结果里可能带 ui 指令（任务卡片、当前画布缩略图）：
            // 单独推给前端并写入本轮历史块，模型上下文里只保留纯数据
            List<Map<String, Object>> resumeBlocks = new ArrayList<>();
            emitExternalUi(writer, resumeBlocks, pending.toolCode(), ok ? result : null);
            runTurn(tenant, user, session, agent, emitter, writer, runtime, context,
                    List.of(resumeMessage), titleOf(session.title()),
                    resumeBlocks, new StringBuilder(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("外部工具恢复失败 callId={}: {}", callId, e.toString());
            writer.send("error", Map.of("code", "AGENT_START_FAILED", "message", safeMessage(e)));
            writer.complete();
        }
        return emitter;
    }

    private String resultText(ExternalCallRow call) {
        if ("DONE".equals(call.status())) {
            String text = stripUi(call.resultJson() == null ? "{}" : call.resultJson());
            return text.length() > 8000 ? text.substring(0, 8000) + "…" : text;
        }
        return call.errorMessage() == null || call.errorMessage().isBlank()
                ? "外部工具执行失败" : call.errorMessage();
    }

    /**
     * ui 是给前端渲染卡片的指令（任务列表、流程图缩略图），
     * 对模型没有增量信息还会占用上下文；正文里剔除，单独走 ui 事件。
     */
    private String stripUi(String json) {
        Map<?, ?> parsed = parsePayload(json);
        if (parsed == null) {
            return json;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        parsed.forEach((key, value) -> copy.put(String.valueOf(key), value));
        copy.remove("ui");
        try {
            // 返回剥过壳的 JSON：即使没有 ui，也把 SDK 多加的那层字符串去掉，
            // 否则模型看到的是一整段带转义的文本，读起来费力也更容易抄错
            return mapper.writeValueAsString(copy);
        } catch (RuntimeException e) {
            return json;
        }
    }

    /**
     * 工具返回的 JSON 可能被 SDK 再序列化一层：直接 readValue 出来是 String 而不是 Map。
     * 实测 tool_result 的 delta 是 {@code "{\"total\":3,...}"}，不剥壳就永远拿不到 ui。
     * 最多剥三层，防止异常数据里出现无限套娃。
     */
    private Map<?, ?> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Object value = mapper.readValue(raw, Object.class);
            for (int depth = 0; depth < 3 && value instanceof String text; depth++) {
                String trimmed = text.trim();
                if (!trimmed.startsWith("{")) {
                    return null;
                }
                value = mapper.readValue(trimmed, Object.class);
            }
            return value instanceof Map<?, ?> map ? map : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 外部工具回传的 ui 指令：推一帧 ui 事件，并写入本轮历史块以便回放。 */
    private void emitExternalUi(SseEventWriter writer, List<Map<String, Object>> blocks,
                                String toolCode, Object result) {
        if (!(result instanceof Map<?, ?> map) || !(map.get("ui") instanceof Map<?, ?> ui)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        ui.forEach((key, value) -> payload.put(String.valueOf(key), value));
        payload.put("tool", toolCode == null ? "" : toolCode);
        writer.send("ui", payload);
        Map<String, Object> block = new LinkedHashMap<>(payload);
        block.put("type", "ui");
        blocks.add(block);
    }

    // ==================== 解析与工具策略 ====================

    /** 当前页可用 Agent：指定 code 则校验；未指定取第一个允许该页面的。 */
    public AgentDefinition resolveAgent(String tenant, String agentCode, String pageKey) {
        AgentDefinition agent = (agentCode == null || agentCode.isBlank())
                ? definitions.list(tenant).stream()
                    .filter(AgentDefinition::enabled)
                    .filter(item -> item.allowsPage(pageKey))
                    .findFirst()
                    .orElseThrow(() -> new BaseplateException("NOT_FOUND", "当前页面没有可用的智能体"))
                : definitions.findByCode(tenant, agentCode)
                    .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在: " + agentCode));
        if (!agent.enabled()) {
            throw new BaseplateException("CONFLICT", "智能体已停用: " + agent.agentCode());
        }
        if (!agent.allowsPage(pageKey)) {
            throw new ForbiddenException("智能体「" + agent.name() + "」不允许在当前页面使用");
        }
        return agent;
    }

    private AgentSession resolveSession(String tenant, String userId, AgentDefinition agent,
                                        ChatCommand command) {
        if (command.sessionId() != null) {
            return sessions.findOwned(tenant, userId, command.sessionId())
                    .map(session -> syncPageKey(tenant, session, command.pageKey()))
                    .orElseGet(() -> newSession(tenant, userId, agent, command.pageKey()));
        }
        return newSession(tenant, userId, agent, command.pageKey());
    }

    /**
     * 继续旧会话时用户可能已经换页。必须把 pageKey 更新为当前页面：
     * 首次调用按当前页注册外部工具，而恢复流用 session.pageKey 重建 Agent，
     * 两边不一致就会出现「工具调用成功、恢复时报 Tool not found」。
     */
    private AgentSession syncPageKey(String tenant, AgentSession session, String pageKey) {
        if (pageKey == null || pageKey.isBlank() || pageKey.equals(session.pageKey())) {
            return session;
        }
        sessions.updatePageKey(tenant, session.id(), pageKey);
        return new AgentSession(session.id(), session.tenantId(), session.agentId(),
                session.userId(), pageKey, session.title(), session.messageCount(),
                session.lastActiveAt(), session.createdAt());
    }

    private AgentSession newSession(String tenant, String userId, AgentDefinition agent, String pageKey) {
        long id = sessions.create(tenant, agent.id(), userId, pageKey);
        return sessions.findOwned(tenant, userId, id)
                .orElseThrow(() -> new BaseplateException("INTERNAL_ERROR", "会话创建失败"));
    }

    /**
     * 计算启用工具与最终审批策略（含安全下限）。
     *
     * <p>除了页面与启停，还按当前用户权限过滤工具：外部工具由前端执行，
     * 一旦暴露给模型就可能被调用，不能只依赖执行期校验。</p>
     */
    private ToolSelection selectTools(String tenant, AgentDefinition agent, String pageKey,
                                      CurrentUser user) {
        List<AgentDefinitionRepository.ToolBinding> bindings =
                definitions.toolBindings(tenant, agent.id());
        Map<String, ToolDescriptor> descriptors = toolRegistry.descriptors().stream()
                .collect(Collectors.toMap(ToolDescriptor::code, Function.identity()));
        Map<String, AgentDefinitionRepository.ToolBinding> bindingMap = bindings.stream()
                .collect(Collectors.toMap(AgentDefinitionRepository.ToolBinding::toolCode,
                        Function.identity(), (left, right) -> left));
        Set<String> enabled = new LinkedHashSet<>();
        Map<String, ApprovalMode> policies = new LinkedHashMap<>();

        // 代码声明的工具：绑定了就尊重启停与策略；没绑定的内置工具默认启用。
        // 否则每上线一个新内置工具（如表单设计），存量 Agent 都要补一遍绑定数据才能用。
        for (ToolDescriptor descriptor : descriptors.values()) {
            // 当前页面用不到的工具不参与本轮对话，避免提示词里出现调不到的工具
            if (!descriptor.allowedOnPage(pageKey)) {
                continue;
            }
            AgentDefinitionRepository.ToolBinding binding = bindingMap.get(descriptor.code());
            if (binding != null && !binding.enabled()) {
                continue;
            }
            if (binding == null && !descriptor.builtin()) {
                continue;
            }
            if (!hasPermission(user, descriptor.permissionCode())) {
                continue;
            }
            ApprovalMode configured = binding == null ? ApprovalMode.INHERIT : binding.approvalMode();
            ApprovalMode effective = ApprovalMode.effective(configured,
                    descriptor.defaultApproval(), descriptor.minApproval());
            policies.put(descriptor.code(), effective);
            if (effective != ApprovalMode.DENY) {
                enabled.add(descriptor.code());
            }
        }

        // 数据库里配置的工具（外部 / HTTP）没有代码声明，只能走绑定表
        for (AgentDefinitionRepository.ToolBinding binding : bindings) {
            if (!binding.enabled() || descriptors.containsKey(binding.toolCode())) {
                continue;
            }
            var row = definitions.findTool(tenant, binding.toolCode()).orElse(null);
            if (row == null || !hasPermission(user, row.permissionCode())) {
                continue;
            }
            boolean pageAllowed = row.pages().isEmpty() || row.pages().contains("*")
                    || row.pages().contains(pageKey);
            if (!pageAllowed) {
                continue;
            }
            ApprovalMode effective = ApprovalMode.effective(binding.approvalMode(),
                    row.defaultApproval(), row.minApproval());
            policies.put(binding.toolCode(), effective);
            if (effective != ApprovalMode.DENY) {
                enabled.add(binding.toolCode());
            }
        }
        return new ToolSelection(enabled, policies);
    }

    private static boolean hasPermission(CurrentUser user, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return true;
        }
        if (user.roles() != null && user.roles().contains("SUPER_ADMIN")) {
            return true;
        }
        return user.permissions() != null && user.permissions().contains(permissionCode);
    }

    /**
     * 技能白名单：null 表示这个 Agent 没配置过技能（全部可用），
     * 空列表表示配置过但全部关闭。两者语义不同，运行时靠 SkillFilter 区分。
     */
    private List<String> skillNames(String tenant, AgentDefinition agent) {
        List<AgentDefinitionRepository.SkillBinding> bindings =
                definitions.skillBindings(tenant, agent.id());
        if (bindings.isEmpty()) {
            return null;
        }
        return bindings.stream()
                .filter(AgentDefinitionRepository.SkillBinding::enabled)
                .map(AgentDefinitionRepository.SkillBinding::skillName)
                .toList();
    }

    private RuntimeContext context(String tenant, CurrentUser user, AgentSession session,
                                   String pageKey) {
        return RuntimeContext.builder()
                // 租户拼进 userId，避免不同租户的同名用户共用模型上下文
                .userId(tenant + ":" + user.userId())
                .sessionId(String.valueOf(session.id()))
                .put("tenantId", tenant)
                .put("pageKey", pageKey)
                .put("principal", user)
                .build();
    }

    // ==================== 落库与观测 ====================

    /** 外部工具由前端执行，暂停阶段产生的空结果不能当失败展示。 */
    private boolean isExternalTool(String tenant, String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return false;
        }
        var spec = toolRegistry.find(toolCode);
        if (spec != null) {
            return spec.descriptor().type() == ToolType.EXTERNAL;
        }
        return definitions.findTool(tenant, toolCode)
                .map(row -> row.toolType() == ToolType.EXTERNAL)
                .orElse(false);
    }

    private void collect(AgentEventMapper.Mapped mapped, List<Map<String, Object>> blocks,
                         StringBuilder answer, StringBuilder thinking) {
        switch (mapped.type()) {
            case "text" -> {
                String delta = String.valueOf(mapped.data().getOrDefault("delta", ""));
                answer.append(delta);
                appendTextBlock(blocks, "text", delta);
            }
            case "thinking" -> {
                String delta = String.valueOf(mapped.data().getOrDefault("delta", ""));
                thinking.append(delta);
                appendTextBlock(blocks, "thinking", delta);
            }
            case "tool_call" -> {
                if ("start".equals(mapped.data().get("phase"))) {
                    Map<String, Object> block = new LinkedHashMap<>(mapped.data());
                    block.put("type", "tool_call");
                    blocks.add(block);
                }
            }
            case "tool_result" -> {
                if ("end".equals(mapped.data().get("phase"))) {
                    Map<String, Object> block = new LinkedHashMap<>(mapped.data());
                    block.put("type", "tool_result");
                    blocks.add(block);
                }
            }
            default -> {
                // 其它事件只在流里展示，不进入历史块
            }
        }
    }

    /**
     * 工具结果里如果带 {@code ui} 字段，就单独推一帧 ui 事件并写入历史块。
     * 前端据此渲染「打开设计器」按钮；页面跳转由用户点击触发，不在流式过程中强跳。
     */
    private void trackToolUi(AgentEventMapper.Mapped mapped,
                            Map<String, StringBuilder> buffers,
                            SseEventWriter writer, List<Map<String, Object>> blocks) {
        if (!"tool_result".equals(mapped.type())) {
            return;
        }
        String phase = String.valueOf(mapped.data().get("phase"));
        String callId = String.valueOf(mapped.data().getOrDefault("callId", ""));
        if ("delta".equals(phase)) {
            buffers.computeIfAbsent(callId, key -> new StringBuilder())
                    .append(String.valueOf(mapped.data().getOrDefault("delta", "")));
            return;
        }
        if (!"end".equals(phase)) {
            return;
        }
        StringBuilder buffer = buffers.remove(callId);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }
        Map<?, ?> parsed = parsePayload(buffer.toString());
        if (parsed == null || !(parsed.get("ui") instanceof Map<?, ?> ui)) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            ui.forEach((key, value) -> payload.put(String.valueOf(key), value));
            payload.put("tool", mapped.data().get("tool"));
            writer.send("ui", payload);
            Map<String, Object> block = new LinkedHashMap<>(payload);
            block.put("type", "ui");
            blocks.add(block);
        } catch (RuntimeException ignored) {
            // 工具输出不是 JSON 或没有 ui 指令，属于正常情况
        }
    }

    /** 连续增量合并进同一个块，回放时不会变成成百上千个碎片。 */
    private void appendTextBlock(List<Map<String, Object>> blocks, String type, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (blocks) {
            if (!blocks.isEmpty()) {
                Map<String, Object> last = blocks.get(blocks.size() - 1);
                if (type.equals(last.get("type"))) {
                    last.put("text", String.valueOf(last.getOrDefault("text", "")) + delta);
                    return;
                }
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", type);
            block.put("text", delta);
            blocks.add(block);
        }
    }

    private long persistAssistant(String tenant, AgentSession session, AgentDefinition agent,
                                  StringBuilder answer, List<Map<String, Object>> blocks,
                                  long startedAt, long firstTokenAt,
                                  String status, Throwable error) {
        try {
            List<Map<String, Object>> snapshot;
            synchronized (blocks) {
                snapshot = List.copyOf(blocks);
            }
            if (error != null) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "error");
                block.put("message", "模型调用失败");
                snapshot = new ArrayList<>(snapshot);
                snapshot.add(block);
            }
            long messageId = messages.append(tenant, session.id(), "ASSISTANT",
                    answer.toString(), mapper.writeValueAsString(snapshot), agent.modelName(),
                    (int) (System.currentTimeMillis() - startedAt));
            recordRun(tenant, session.id(), messageId, agent,
                    System.currentTimeMillis() - startedAt, firstTokenAt, startedAt, status, error);
            return messageId;
        } catch (RuntimeException e) {
            log.error("智能体回答落库失败 sessionId={}", session.id(), e);
            return 0L;
        }
    }

    private void recordRun(String tenant, long sessionId, long messageId, AgentDefinition agent,
                           long latency, long firstTokenAt, long startedAt, String status,
                           Throwable error) {
        try {
            runs.record(new AgentRunRepository.RunRecord(
                    tenant, sessionId, messageId, null, agent.modelName(),
                    0, 0, 0, 0, 0,
                    (int) latency,
                    firstTokenAt < 0 ? 0 : (int) (firstTokenAt - startedAt),
                    status,
                    error == null ? null : "AGENT_STREAM_ERROR",
                    error == null ? null : safeMessage(error)));
        } catch (RuntimeException e) {
            log.warn("智能体运行记录写入失败 sessionId={}: {}", sessionId, e.toString());
        }
    }

    private String userBlocks(String message) {
        return mapper.writeValueAsString(List.of(Map.of("type", "text", "text", message)));
    }

    private static String titleOf(String message) {
        String text = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "智能体调用失败";
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }

    /** 供配置页「测试连接」复用同一套模型装配与超时口径。 */
    public Map<String, Object> testConnection(String tenant, long agentId) {
        AgentDefinition agent = definitions.find(tenant, agentId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
        long started = System.currentTimeMillis();
        ReActAgent runtime = runtimeFactory.get(tenant, agent, Set.of(), Map.of(),
                skillNames(tenant, agent));
        RuntimeContext context = RuntimeContext.builder()
                .userId(tenant + ":" + "config-test")
                .sessionId("config-test-" + agentId)
                .put("tenantId", tenant)
                .put("pageKey", "*")
                .build();
        // ReActAgent 只提供 call(List<Msg>, ctx) / call(String, ctx)，不能直接传 UserMessage
        String reply = runtime.call("请只回复：pong", context)
                .block(Duration.ofSeconds(30))
                .getTextContent();
        return Map.of("ok", true, "latencyMs", System.currentTimeMillis() - started,
                "reply", reply == null ? "" : reply);
    }
}
