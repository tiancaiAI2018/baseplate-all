package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.AgentChatService;
import com.example.baseplate.agent.application.AgentConfigService;
import com.example.baseplate.agent.application.AgentConfigService.DefinitionRequest;
import com.example.baseplate.agent.application.AgentConfigService.DefinitionView;
import com.example.baseplate.agent.application.AgentConfigService.SkillBindingRequest;
import com.example.baseplate.agent.application.AgentConfigService.SkillBindingView;
import com.example.baseplate.agent.application.AgentConfigService.ToolBindingRequest;
import com.example.baseplate.agent.application.AgentConfigService.ToolView;
import com.example.baseplate.agent.domain.AgentDefinition;
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

/** 智能体配置：模型、提示词、页面绑定、工具启停与审批策略。 */
@RestController
@RequestMapping("/api/agent/definitions")
public class AgentConfigController {

    private final AgentConfigService configService;
    private final AgentChatService chatService;
    private final CurrentUserProvider currentUser;

    public AgentConfigController(AgentConfigService configService, AgentChatService chatService,
                                 CurrentUserProvider currentUser) {
        this.configService = configService;
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @RequiresPermission("agent:config:view")
    public List<DefinitionView> list() {
        return configService.list(tenant());
    }

    @PostMapping
    @RequiresPermission("agent:config:edit")
    public DefinitionView create(@RequestBody DefinitionRequest request) {
        return configService.create(tenant(), request);
    }

    @PutMapping("/{id}")
    @RequiresPermission("agent:config:edit")
    public DefinitionView update(@PathVariable long id, @RequestBody DefinitionRequest request) {
        return configService.update(tenant(), id, request);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> delete(@PathVariable long id) {
        configService.delete(tenant(), id);
        return Map.of("deleted", true);
    }

    /** 工具目录 + 策略。内置工具只返回只读元数据，前端据此禁用编辑/删除按钮。 */
    @GetMapping("/{id}/tools")
    @RequiresPermission("agent:config:view")
    public List<ToolView> tools(@PathVariable long id) {
        return configService.tools(tenant(), id);
    }

    @PutMapping("/{id}/tools")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> saveTools(@PathVariable long id,
                                         @RequestBody List<ToolBindingRequest> request) {
        configService.saveTools(tenant(), id, request);
        return Map.of("saved", request.size());
    }

    /** 技能绑定：只返回技能索引与启停，正文仍在本地目录。 */
    @GetMapping("/{id}/skills")
    @RequiresPermission("agent:config:view")
    public List<SkillBindingView> skills(@PathVariable long id) {
        return configService.skills(tenant(), id);
    }

    @PutMapping("/{id}/skills")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> saveSkills(@PathVariable long id,
                                          @RequestBody List<SkillBindingRequest> request) {
        configService.saveSkills(tenant(), id, request);
        return Map.of("saved", request.size());
    }

    /** 测试连接：真实调用一次模型，返回耗时；失败由全局异常处理返回具体原因。 */
    @PostMapping("/{id}/test")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> test(@PathVariable long id) {
        return chatService.testConnection(tenant(), id);
    }

    private String tenant() {
        CurrentUser user = currentUser.current();
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
