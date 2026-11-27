package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentContextService;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会话上下文用量与手动压缩。 */
@RestController
@RequestMapping("/api/agent/sessions")
public class AgentContextController {

    private final AgentContextService contextService;
    private final CurrentUserProvider currentUser;

    public AgentContextController(AgentContextService contextService,
                                  CurrentUserProvider currentUser) {
        this.contextService = contextService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/context-usage")
    @RequiresPermission("agent:chat")
    public AgentContextService.ContextUsage usage(@PathVariable long id) {
        CurrentUser user = currentUser.current();
        return contextService.usage(tenant(user), user.userId(), id);
    }

    @PostMapping("/{id}/compact")
    @RequiresPermission("agent:chat")
    public AgentContextService.CompactResult compact(@PathVariable long id,
                                                     @RequestParam(defaultValue = "20") int keepMessages) {
        CurrentUser user = currentUser.current();
        return contextService.compact(tenant(user), user.userId(), id, keepMessages);
    }

    private String tenant(CurrentUser user) {
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
