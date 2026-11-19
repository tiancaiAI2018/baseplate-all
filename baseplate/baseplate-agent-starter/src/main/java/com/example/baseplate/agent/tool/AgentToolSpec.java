package com.example.baseplate.agent.tool;

/** 工具元数据 + 可注册进 agentscope Toolkit 的处理器对象。 */
public record AgentToolSpec(ToolDescriptor descriptor, Object handler) {
}
