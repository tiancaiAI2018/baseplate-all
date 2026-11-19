package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentChatService;
import com.example.baseplate.agent.application.AgentChatService.ChatCommand;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** 悬浮助手入口：SSE 对话 + 按页面查询可用 Agent。 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private final AgentChatService chatService;
    private final CurrentUserProvider currentUser;

    public AgentChatController(AgentChatService chatService, CurrentUserProvider currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    public record ChatRequest(String agentCode, Long sessionId, String pageKey, String message) {
    }

    /**
     * 流式对话。用 POST 而不是 EventSource：问题文本可能很长，且 Authorization 头不能丢。
     * 返回 Content-Type: text/event-stream，事件见 AgentEventMapper 与 done/error。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("agent:chat")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        CurrentUser user = currentUser.current();
        return chatService.stream(tenant(user), user,
                new ChatCommand(request.agentCode(), request.sessionId(),
                        request.pageKey(), request.message()));
    }

    /**
     * 页面挂载时调用：当前页有没有可用 Agent。没有就返回 enabled=false，前端隐藏气泡。
     * 这里只返回展示信息，不返回模型地址与密钥。
     */
    @GetMapping("/chat/bootstrap")
    @RequiresPermission("agent:chat")
    public Map<String, Object> bootstrap(@RequestParam String pageKey) {
        CurrentUser user = currentUser.current();
        try {
            AgentDefinition agent = chatService.resolveAgent(tenant(user), null, pageKey);
            return Map.of("enabled", true, "agentCode", agent.agentCode(),
                    "agentName", agent.name(), "pageKey", pageKey);
        } catch (RuntimeException e) {
            return Map.of("enabled", false, "pageKey", pageKey);
        }
    }

    private String tenant(CurrentUser user) {
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
