package com.example.baseplate.agent.tool;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;

import java.util.List;
import java.util.Set;

/** 平台只读工具的内置声明：用户只能启停，不能改描述、不能删除。 */
public final class PlatformToolProvider implements AgentToolProvider {

    private final PlatformTools handler = new PlatformTools();

    @Override
    public List<AgentToolSpec> tools() {
        return List.of(
                new AgentToolSpec(new ToolDescriptor(
                        "platform_whoami", "当前用户信息",
                        "返回当前登录用户、租户和所在页面。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        Set.of(), "agent:chat", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "platform_current_time", "服务器时间",
                        "返回服务器当前时间（ISO-8601）。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        Set.of(), "agent:chat", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler));
    }
}
