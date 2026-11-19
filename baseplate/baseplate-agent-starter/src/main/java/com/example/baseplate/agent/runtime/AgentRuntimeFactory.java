package com.example.baseplate.agent.runtime;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.application.SkillIndexService;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.model.ModelProviderFactory;
import com.example.baseplate.agent.tool.AgentToolSpec;
import com.example.baseplate.agent.tool.HttpApiTool;
import com.example.baseplate.agent.tool.ToolDescriptor;
import com.example.baseplate.agent.tool.ToolRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 按「Agent 配置 + 页面」构建 ReActAgent 并缓存。
 *
 * <p>agentscope 的 Agent 是无状态的（会话状态走 AgentStateStore），
 * 所以同一份配置可以复用；缓存键里带 revision 与页面，配置一改自然换新实例。</p>
 */
public final class AgentRuntimeFactory {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 BASEPLATE 业务基盘的控制台助手。
            回答要简短、准确；不确定的信息先查工具，不要编造。
            只能调用提供的工具，不要尝试访问当前页面之外的功能。
            """;

    private final ToolRegistry toolRegistry;
    private final ModelProviderFactory modelFactory;
    private final AgentProperties properties;
    private final AgentStateStore stateStore;
    private final SkillIndexService skillIndexService;
    private final AgentDefinitionRepository definitionRepository;
    private final Map<String, ReActAgent> cache = new ConcurrentHashMap<>();

    public AgentRuntimeFactory(ToolRegistry toolRegistry, ModelProviderFactory modelFactory,
                               AgentProperties properties, AgentStateStore stateStore,
                               SkillIndexService skillIndexService,
                               AgentDefinitionRepository definitionRepository) {
        this.toolRegistry = toolRegistry;
        this.modelFactory = modelFactory;
        this.properties = properties;
        this.stateStore = stateStore;
        this.skillIndexService = skillIndexService;
        this.definitionRepository = definitionRepository;
    }

    public ReActAgent get(String tenant, AgentDefinition definition, String pageKey,
                          Set<String> enabledTools, Map<String, ApprovalMode> policies,
                          List<String> skillNames) {
        String policyKey = policies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        // null 表示未配置技能（全部可用），空列表表示明确不使用技能；两者缓存必须分开
        String skillKey = skillNames == null ? "*"
                : String.join(",", skillNames.stream().sorted().toList());
        String cacheKey = tenant + ":" + definition.id() + ":" + definition.revision() + ":" + pageKey
                + ":" + String.join(",", enabledTools.stream().sorted().toList())
                + ":" + policyKey + ":" + skillKey;
        return cache.computeIfAbsent(cacheKey,
                key -> build(tenant, definition, pageKey, enabledTools, policies, skillNames));
    }

    private ReActAgent build(String tenant, AgentDefinition definition, String pageKey,
                             Set<String> enabledTools, Map<String, ApprovalMode> policies,
                             List<String> skillNames) {
        Toolkit toolkit = new Toolkit();
        // 同一个处理器对象可能承载多个工具，按实例去重后只注册一次。
        // 外部工具没有服务端 handler，执行由前端完成。
        Set<Object> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AgentToolSpec spec : toolRegistry.all()) {
            if (spec.handler() != null && spec.descriptor().type() != ToolType.EXTERNAL) {
                handlers.add(spec.handler());
            }
        }
        handlers.forEach(toolkit::registerTool);

        // 代码声明的外部工具：只下发 schema，参数与结果走 external_tool_call 事件
        for (AgentToolSpec spec : toolRegistry.all()) {
            ToolDescriptor descriptor = spec.descriptor();
            if (descriptor.type() == ToolType.EXTERNAL
                    && enabledTools.contains(descriptor.code())
                    && descriptor.allowedOnPage(pageKey)) {
                Map<String, Object> schema = descriptor.paramsSchema().isEmpty()
                        ? Map.of("type", "object", "properties", Map.of())
                        : descriptor.paramsSchema();
                toolkit.registerSchema(ToolSchema.builder()
                        .name(descriptor.code())
                        .description(descriptor.description())
                        .parameters(schema)
                        .build());
            }
        }

        for (ToolDescriptor descriptor : toolRegistry.descriptors()) {
            boolean allowed = enabledTools.contains(descriptor.code())
                    && descriptor.allowedOnPage(pageKey);
            if (!allowed) {
                toolkit.removeTool(descriptor.code());
            }
        }

        // 数据库里配置的外部工具：只注册 schema，执行由前端完成。
        // 代码工具优先；同名自定义工具在创建时就被拒绝。
        for (String toolCode : enabledTools) {
            if (toolRegistry.find(toolCode) != null) {
                continue;
            }
            definitionRepository.findTool(tenant, toolCode).ifPresent(row -> {
                boolean pageAllowed = row.pages().isEmpty() || row.pages().contains("*")
                        || row.pages().contains(pageKey);
                if (row.toolType() == ToolType.EXTERNAL && pageAllowed) {
                    Map<String, Object> schema = row.paramsSchema().isEmpty()
                            ? Map.of("type", "object", "properties", Map.of())
                            : row.paramsSchema();
                    toolkit.registerSchema(ToolSchema.builder()
                            .name(row.toolCode())
                            .description(row.description() == null ? row.name() : row.description())
                            .parameters(schema)
                            .build());
                } else if (row.toolType() == ToolType.CUSTOM_INTERNAL && pageAllowed) {
                    // 服务端 HTTP 工具：参数、超时、内网拦截都在 HttpApiTool 里
                    toolkit.registerAgentTool(new HttpApiTool(row.toolCode(), row.name(),
                            row.description(), row.paramsSchema(), row.config(),
                            row.permissionCode()));
                }
            });
        }

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("baseplate-" + definition.agentCode())
                .sysPrompt(systemPrompt(definition, pageKey))
                .model(modelFactory.build(definition))
                .toolkit(toolkit)
                .maxIters(properties.getMaxIters())
                .stateStore(stateStore);

        // HITL：把配置为 ASK 的工具登记为需确认；DENY 已在上面的 enabledTools 里排除。
        // 其余放行工具必须显式登记 ALLOW，否则会落到 PermissionEngine 的默认 ASK。
        PermissionContextState permission = permissionContext(policies);
        if (!permission.isTrivial()) {
            builder.permissionContext(permission);
        }
        // 技能正文在本地目录；目录为空时不挂载，避免框架对空仓库直接报错
        if (skillIndexService.available()) {
            builder.skillRepository(skillIndexService.repository());
            if (skillNames != null) {
                // 绑定为空 = 这个 Agent 明确不使用技能；null 才表示未配置、全部可用
                builder.skillFilter(skillNames.isEmpty()
                        ? SkillFilter.none()
                        : SkillFilter.only(skillNames.toArray(String[]::new)));
            }
        }
        return builder.build();
    }

    /**
     * 把平台的审批策略翻译成 agentscope 的权限上下文。
     *
     * <p>{@link io.agentscope.core.permission.PermissionEngine} 的规则匹配顺序是
     * 拒绝 → 确认 → 工具自检 → 放行 → 模式兜底；一旦启用权限上下文（非 trivial），
     * 没有任何规则命中的工具会走兜底默认值 <b>ASK</b>。如果只登记 ASK 规则，
     * 其它所有工具都会意外变成「需人工确认」，因此这里必须为放行工具补上 ALLOW 规则。</p>
     *
     * <p>没有 ASK 工具时返回 trivial 上下文，让运行时继续走框架的轻量权限路径。</p>
     */
    public static PermissionContextState permissionContext(Map<String, ApprovalMode> policies) {
        PermissionContextState.Builder permission = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT);
        boolean hasAskRule = false;
        for (Map.Entry<String, ApprovalMode> entry : policies.entrySet()) {
            if (entry.getValue() == ApprovalMode.ASK) {
                permission.addAskRule(entry.getKey(),
                        rule(entry.getKey(), PermissionBehavior.ASK));
                hasAskRule = true;
            }
        }
        if (!hasAskRule) {
            return permission.build();
        }
        for (Map.Entry<String, ApprovalMode> entry : policies.entrySet()) {
            if (entry.getValue() == ApprovalMode.ALLOW) {
                permission.addAllowRule(entry.getKey(),
                        rule(entry.getKey(), PermissionBehavior.ALLOW));
            }
        }
        return permission.build();
    }

    /** ruleContent 为 null 表示匹配该工具的所有调用参数。 */
    private static PermissionRule rule(String toolCode, PermissionBehavior behavior) {
        return new PermissionRule(toolCode, null, behavior, "agent-config");
    }

    /**
     * 系统提示词 = 配置内容 + 页面边界。
     *
     * <p>不再拼接「可用工具」列表：工具已经由 toolkit 的 schema 暴露给模型，
     * 在提示词里重复一遍只会让前缀随工具集变化，破坏厂商侧的 prompt 缓存。</p>
     */
    private String systemPrompt(AgentDefinition definition, String pageKey) {
        String base = definition.systemPrompt() == null || definition.systemPrompt().isBlank()
                ? DEFAULT_SYSTEM_PROMPT : definition.systemPrompt();
        return base + "\n\n[运行边界]\n当前页面: " + pageKey
                + "\n只能处理与当前页面相关的事务，超出范围请直接说明无法处理。"
                + "\n工具返回失败时必须如实告知用户失败原因，绝不能声称成功。";
    }

    /** 配置变更后清掉旧实例，避免继续使用旧提示词/旧工具集。 */
    public void invalidate(long agentId) {
        String marker = ":" + agentId + ":";
        cache.keySet().removeIf(key -> key.contains(marker));
    }

    /** 技能目录重扫后所有实例都要重建，技能快照不是按 Agent 隔离的。 */
    public void invalidateAll() {
        cache.clear();
    }
}
