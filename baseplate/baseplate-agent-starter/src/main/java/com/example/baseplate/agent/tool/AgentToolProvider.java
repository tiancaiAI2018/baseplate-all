package com.example.baseplate.agent.tool;

import java.util.List;

/**
 * 工具提供者 SPI：工作流、身份等模块各自声明自己的工具，
 * agent 模块不反向依赖业务模块，新增工具组只需多一个 Spring Bean。
 */
public interface AgentToolProvider {

    List<AgentToolSpec> tools();
}
