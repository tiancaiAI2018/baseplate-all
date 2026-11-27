package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentChatService;
import com.example.baseplate.agent.application.AgentChatService.ChatCommand;
import com.example.baseplate.agent.application.AgentSessionService;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.api.annotation.OpenApi;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.OpenApiSubject;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 开放平台上的 Agent 交互入口。
 *
 * <p>业务全部委托给现有 Service；身份由 OpenApiAuthFilter 写入 CurrentUserProvider，
 * 所以这里和内部 controller 一样取 currentUser.current()。</p>
 */
@RestController
@RequestMapping("/openapi/agent")
public class OpenAgentController {

    private final AgentChatService chatService;
    private final AgentSessionService sessionService;
    private final CurrentUserProvider currentUser;

    public OpenAgentController(AgentChatService chatService,
                               AgentSessionService sessionService,
                               CurrentUserProvider currentUser) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.currentUser = currentUser;
    }

    public record ChatRequest(String agentCode, Long sessionId, String pageKey, String message) {
    }

    public record ExternalResult(boolean ok, Object result, String error) {
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @OpenApi(code = "agent.open.chat", name = "Agent 流式对话", group = "智能体",
            subject = OpenApiSubject.USER, permission = "agent:chat", scope = "write")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        CurrentUser user = currentUser.current();
        return chatService.stream(tenant(user), user,
                new ChatCommand(request.agentCode(), request.sessionId(),
                        request.pageKey(), request.message()));
    }

    @GetMapping("/chat/bootstrap")
    @OpenApi(code = "agent.open.bootstrap", name = "Agent 可用性与会话", group = "智能体",
            subject = OpenApiSubject.USER, permission = "agent:chat", scope = "read")
    public Map<String, Object> bootstrap(@RequestParam String pageKey) {
        CurrentUser user = currentUser.current();
        try {
            AgentDefinition agent = chatService.resolveAgent(tenant(user), null, pageKey);
            return Map.of("enabled", true, "agentId", agent.id(), "agentCode", agent.agentCode(),
                    "agentName", agent.name(), "pageKey", pageKey);
        } catch (RuntimeException e) {
            return Map.of("enabled", false, "pageKey", pageKey);
        }
    }

    @GetMapping("/sessions")
    @OpenApi(code = "agent.open.sessionList", name = "Agent 会话列表", group = "智能体",
            subject = OpenApiSubject.USER, permission = "agent:session:view", scope = "read")
    public List<?> sessions(@RequestParam(defaultValue = "false") boolean all,
                            @RequestParam(defaultValue = "50") int limit) {
        CurrentUser user = currentUser.current();
        return sessionService.list(tenant(user), user, all, limit);
    }

    @GetMapping("/sessions/{id}/messages")
    @OpenApi(code = "agent.open.sessionMessages", name = "Agent 会话消息", group = "智能体",
            subject = OpenApiSubject.USER, permission = "agent:session:view", scope = "read")
    public List<?> messages(@PathVariable long id,
                            @RequestParam(defaultValue = "false") boolean all) {
        CurrentUser user = currentUser.current();
        return sessionService.messages(tenant(user), user, id, all);
    }

    @PostMapping(value = "/external-calls/{callId}/result", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @OpenApi(code = "agent.open.externalResult", name = "外部工具结果回传", group = "智能体",
            subject = OpenApiSubject.USER, permission = "agent:chat", scope = "write")
    public SseEmitter externalResult(@PathVariable String callId, @RequestBody ExternalResult request) {
        CurrentUser user = currentUser.current();
        return chatService.resumeExternal(tenant(user), user, callId,
                request.ok(), request.result(), request.error());
    }

    private String tenant(CurrentUser user) {
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
