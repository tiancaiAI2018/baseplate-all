package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentConfigService;
import com.example.baseplate.agent.application.AgentConfigService.ToolDefinitionRequest;
import com.example.baseplate.agent.application.AgentConfigService.ToolDefinitionView;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 工具目录：内置只读展示，自定义外部工具可增删改。 */
@RestController
@RequestMapping("/api/agent/tools")
public class AgentToolDirectoryController {

    private final AgentConfigService configService;
    private final CurrentUserProvider currentUser;

    public AgentToolDirectoryController(AgentConfigService configService,
                                        CurrentUserProvider currentUser) {
        this.configService = configService;
        this.currentUser = currentUser;
    }

    @GetMapping("/directory")
    @RequiresPermission("agent:config:view")
    public List<ToolDefinitionView> directory() {
        return configService.toolDirectory(tenant());
    }

    @PostMapping("/directory")
    @RequiresPermission("agent:config:edit")
    public ToolDefinitionView create(@RequestBody ToolDefinitionRequest request) {
        return configService.createTool(tenant(), request);
    }

    @PutMapping("/directory/{toolCode}")
    @RequiresPermission("agent:config:edit")
    public ToolDefinitionView update(@PathVariable String toolCode,
                                     @RequestBody ToolDefinitionRequest request) {
        return configService.updateTool(tenant(), toolCode, request);
    }

    @DeleteMapping("/directory/{toolCode}")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> delete(@PathVariable String toolCode) {
        configService.deleteTool(tenant(), toolCode);
        return Map.of("deleted", true);
    }

    private String tenant() {
        CurrentUser user = currentUser.current();
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
