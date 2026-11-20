package com.example.baseplate.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 *
 * <p>启动期把所有 {@link AgentToolProvider} 的工具收进来并校验重名——重名意味着模型
 * 可能调用到错误实现，属于必须启动失败的配置错误。页面与 Agent 绑定在运行时再过滤。</p>
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentToolSpec> specs;

    public ToolRegistry(List<AgentToolProvider> providers) {
        Map<String, AgentToolSpec> collected = new LinkedHashMap<>();
        for (AgentToolProvider provider : providers) {
            for (AgentToolSpec spec : provider.tools()) {
                String code = spec.descriptor().code();
                AgentToolSpec previous = collected.putIfAbsent(code, spec);
                if (previous != null) {
                    // 外部工具的 handler 为 null，不能在这里取 class，否则报错会变成 NPE
                    throw new IllegalStateException("工具编码重复: " + code);
                }
            }
        }
        this.specs = Map.copyOf(collected);
        log.info("智能体工具注册完成: {} 个", specs.size());
    }

    public List<ToolDescriptor> descriptors() {
        return specs.values().stream().map(AgentToolSpec::descriptor).toList();
    }

    /** 全部工具，供运行时注册处理器后按启用集合裁剪。 */
    public List<AgentToolSpec> all() {
        return List.copyOf(specs.values());
    }

    public AgentToolSpec find(String code) {
        return specs.get(code);
    }
}
