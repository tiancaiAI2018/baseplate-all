package com.example.baseplate.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;

import java.time.OffsetDateTime;

/**
 * 平台内置工具：只读、无副作用，用来验证工具链路与会话上下文传递。
 * 业务模块（工作流等）通过 {@link AgentToolProvider} 贡献自己的工具，不需要改这里。
 */
public final class PlatformTools {

    @Tool(name = "platform_whoami",
            description = "返回当前登录用户、租户和所在页面。用户问「我是谁」「我在哪个页面」时使用。")
    public String whoami(RuntimeContext ctx) {
        return "userId=" + ctx.getUserId()
                + ", tenantId=" + ctx.get("tenantId")
                + ", pageKey=" + ctx.get("pageKey");
    }

    @Tool(name = "platform_current_time",
            description = "返回服务器当前时间（ISO-8601），用于与时间相关的问题。")
    public String currentTime(RuntimeContext ctx) {
        return OffsetDateTime.now().toString();
    }
}
