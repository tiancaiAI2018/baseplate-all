package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentChatService;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
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
 * HITL 审批：查询待确认项 + 同意/拒绝。
 * 决策本身返回 SSE，前端可以直接把恢复后的输出接在原来的对话气泡里。
 */
@RestController
@RequestMapping("/api/agent/hitl")
public class AgentHitlController {

    private final AgentChatService chatService;
    private final CurrentUserProvider currentUser;

    public AgentHitlController(AgentChatService chatService, CurrentUserProvider currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    public record DecisionRequest(boolean approved, String comment) {
    }

    @GetMapping("/pending")
    @RequiresPermission("agent:chat")
    public List<Map<String, Object>> pending(@RequestParam long sessionId) {
        CurrentUser user = currentUser.current();
        return chatService.pendingHitl(tenant(user), user, sessionId);
    }

    @PostMapping(value = "/{id}/decision", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("agent:chat")
    public SseEmitter decide(@PathVariable long id, @RequestBody DecisionRequest request) {
        CurrentUser user = currentUser.current();
        return chatService.resumeHitl(tenant(user), user, id, request.approved(), request.comment());
    }

    private String tenant(CurrentUser user) {
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
