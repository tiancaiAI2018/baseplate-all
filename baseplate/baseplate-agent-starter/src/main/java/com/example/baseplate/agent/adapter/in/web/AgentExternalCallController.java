package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentChatService;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 外部工具结果回传：前端执行完后调用，服务端恢复 Agent 并继续输出 SSE。
 * 决策结果与 HITL 一样走 SSE，前端可以原地续写同一个对话气泡。
 */
@RestController
@RequestMapping("/api/agent/external-calls")
public class AgentExternalCallController {

    private final AgentChatService chatService;
    private final CurrentUserProvider currentUser;

    public AgentExternalCallController(AgentChatService chatService,
                                       CurrentUserProvider currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    public record ResultRequest(boolean ok, Object result, String error) {
    }

    @PostMapping(value = "/{callId}/result", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("agent:chat")
    public SseEmitter result(@PathVariable String callId, @RequestBody ResultRequest request) {
        CurrentUser user = currentUser.current();
        String tenant = user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
        return chatService.resumeExternal(tenant, user, callId,
                request.ok(), request.result(), request.error());
    }
}
