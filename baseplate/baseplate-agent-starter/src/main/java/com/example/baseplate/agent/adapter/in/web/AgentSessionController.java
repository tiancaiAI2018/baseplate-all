package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentSessionService;
import com.example.baseplate.agent.domain.AgentSession;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 会话历史：列表、消息回放、删除、回复评分。 */
@RestController
@RequestMapping("/api/agent/sessions")
public class AgentSessionController {

    private final AgentSessionService sessionService;
    private final CurrentUserProvider currentUser;

    public AgentSessionController(AgentSessionService sessionService,
                                  CurrentUserProvider currentUser) {
        this.sessionService = sessionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @RequiresPermission("agent:session:view")
    public List<AgentSession> list(@RequestParam(defaultValue = "false") boolean all,
                                   @RequestParam(defaultValue = "50") int limit) {
        CurrentUser user = currentUser.current();
        return sessionService.list(tenant(user), user, all, limit);
    }

    @GetMapping("/{id}/messages")
    @RequiresPermission("agent:session:view")
    public List<AgentSessionService.MessageView> messages(@PathVariable long id,
                                                          @RequestParam(defaultValue = "false") boolean all) {
        CurrentUser user = currentUser.current();
        return sessionService.messages(tenant(user), user, id, all);
    }

    /**
     * 对自己的助手回复打分：有用 / 没用；重复点击同一项可清除。
     * 只能评自己会话里的 ASSISTANT 消息，服务端按 user_id 校验。
     */
    @PostMapping("/messages/{messageId}/feedback")
    @RequiresPermission("agent:chat")
    public Map<String, Object> rate(@PathVariable long messageId,
                                    @RequestBody FeedbackRequest request) {
        CurrentUser user = currentUser.current();
        sessionService.rate(tenant(user), user, messageId, request.rating());
        return Map.of("rated", true, "rating", request.rating() == null ? "" : request.rating());
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("agent:session:view")
    public Map<String, Object> delete(@PathVariable long id) {
        sessionService.delete(tenant(currentUser.current()), currentUser.current(), id);
        return Map.of("deleted", true);
    }

    public record FeedbackRequest(String rating) {
    }

    private String tenant(CurrentUser user) {
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
