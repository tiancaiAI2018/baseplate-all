package com.example.baseplate.agent.runtime;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentModelCallRepository;
import com.example.baseplate.agent.application.AgentCompactionConfig;
import com.example.baseplate.agent.application.RecordingModel;
import com.example.baseplate.agent.application.SkillIndexService;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.config.ToolExposureMode;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.model.ModelProviderFactory;
import com.example.baseplate.agent.tool.AgentToolSpec;
import com.example.baseplate.agent.tool.HttpApiTool;
import com.example.baseplate.agent.tool.ToolCliTool;
import com.example.baseplate.agent.tool.ToolDescriptor;
import com.example.baseplate.agent.tool.ToolDirectory;
import com.example.baseplate.agent.tool.ToolMetaTool;
import com.example.baseplate.agent.tool.ToolRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 按 Agent 配置构建 ReActAgent 并缓存。
 *
 * <p>agentscope 的 Agent 是无状态的（会话状态走 AgentStateStore），
 * 所以同一份配置可以复用；缓存键里带 revision，配置一改自然换新实例。</p>
 *
 * <p>pageKey 只在入口处决定「这个 Agent / 工具能否在本页使用」，不进入提示词与缓存键：
 * 提示词前缀保持稳定，才能命中厂商侧的 prompt 缓存。</p>
 */
public final class AgentRuntimeFactory {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 BASEPLATE 业务基盘的控制台助手。
            回答要简短、准确；不确定的信息先查工具，不要编造。
            只能调用提供的工具，不要尝试访问工具之外的功能。
            """;

    private static final String SKILL_MODE_CLI = "cli";
    private static final String SKILL_MODE_META = "meta";
    private static final String SKILL_LOAD_TOOL = "load_skill_through_path";

    private final ToolRegistry toolRegistry;
    private final ModelProviderFactory modelFactory;
    private final AgentProperties properties;
    private final AgentStateStore stateStore;
    private final SkillIndexService skillIndexService;
    private final AgentDefinitionRepository definitionRepository;
    private final AgentModelCallRepository modelCallRepository;
    private final Map<String, ReActAgent> cache = new ConcurrentHashMap<>();

    public AgentRuntimeFactory(ToolRegistry toolRegistry, ModelProviderFactory modelFactory,
                               AgentProperties properties, AgentStateStore stateStore,
                               SkillIndexService skillIndexService,
                               AgentDefinitionRepository definitionRepository,
                               AgentModelCallRepository modelCallRepository) {
        this.toolRegistry = toolRegistry;
        this.modelFactory = modelFactory;
        this.properties = properties;
        this.stateStore = stateStore;
        this.skillIndexService = skillIndexService;
        this.definitionRepository = definitionRepository;
        this.modelCallRepository = modelCallRepository;
    }

    public ReActAgent get(String tenant, AgentDefinition definition,
                          Set<String> enabledTools, Map<String, ApprovalMode> policies,
                          List<String> skillNames) {
        String policyKey = policies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        // null 表示未配置技能（全部可用），空列表表示明确不使用技能；两者缓存必须分开
        String skillKey = skillNames == null ? "*"
                : String.join(",", skillNames.stream().sorted().toList());
        // enabledTools 已在 selectTools 里按 pageKey 筛过：页面差异会体现在工具集合上，
        // 集合相同就共享实例，无需再把 pageKey 拼进缓存键。
        String cacheKey = tenant + ":" + definition.id() + ":" + definition.revision()
                + ":" + properties.getToolExposureMode().name()
                + ":" + String.join(",", enabledTools.stream().sorted().toList())
                + ":" + policyKey + ":" + skillKey;
        return cache.computeIfAbsent(cacheKey,
                key -> build(tenant, definition, enabledTools, policies, skillNames));
    }

    private ReActAgent build(String tenant, AgentDefinition definition,
                             Set<String> enabledTools, Map<String, ApprovalMode> policies,
                             List<String> skillNames) {
        Toolkit toolkit = new Toolkit();
        ToolExposureMode mode = properties.getToolExposureMode();
        boolean compact = mode != ToolExposureMode.DIRECT;
        // 影子 toolkit 只用于入口工具分发执行，其 schema 不进模型上下文
        Toolkit internalToolkit = new Toolkit();

        // 同一个处理器对象可能承载多个工具，按实例去重后只注册一次。
        // 收敛模式下 ASK/EXTERNAL 也走入口：ASK 由入口 checkPermissions 代报，EXTERNAL 抛
        // ToolSuspendException，因此它们不再单独注册进主 toolkit 占用模型 token。
        Set<Object> compactHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> approvalHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AgentToolSpec spec : toolRegistry.all()) {
            ToolDescriptor descriptor = spec.descriptor();
            if (spec.handler() == null
                    || descriptor.type() == ToolType.EXTERNAL
                    || !enabledTools.contains(descriptor.code())) {
                continue;
            }
            if (policies.get(descriptor.code()) == ApprovalMode.ASK) {
                approvalHandlers.add(spec.handler());
                if (compact) {
                    compactHandlers.add(spec.handler());
                }
            } else {
                compactHandlers.add(spec.handler());
            }
        }
        compactHandlers.forEach(compact ? internalToolkit::registerTool : toolkit::registerTool);
        if (!compact) {
            approvalHandlers.forEach(toolkit::registerTool);
        }

        // registerTool 会注册 handler 的全部方法，未启用或应收敛的 code 必须逐个摘掉
        for (ToolDescriptor descriptor : toolRegistry.descriptors()) {
            String code = descriptor.code();
            if (descriptor.type() == ToolType.EXTERNAL) {
                continue;
            }
            boolean enabled = enabledTools.contains(code);
            boolean ask = policies.get(code) == ApprovalMode.ASK;
            if (!(enabled && (!ask || compact))) {
                internalToolkit.removeTool(code);
            }
            if (!(enabled && (ask || !compact))) {
                toolkit.removeTool(code);
            }
        }

        // 代码声明的外部工具：直连模式下只下发 schema；收敛模式由入口工具发现并转外部执行
        for (AgentToolSpec spec : toolRegistry.all()) {
            if (compact) {
                break;
            }
            ToolDescriptor descriptor = spec.descriptor();
            // 页面可用性已在入口的 selectTools 里校验，这里只按启用集合注册
            if (descriptor.type() == ToolType.EXTERNAL
                    && enabledTools.contains(descriptor.code())) {
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

        // 数据库里配置的外部工具：直连模式只注册 schema；代码工具优先。
        // 同名自定义工具在创建时就被拒绝。
        for (String toolCode : enabledTools) {
            if (toolRegistry.find(toolCode) != null) {
                continue;
            }
            definitionRepository.findTool(tenant, toolCode).ifPresent(row -> {
                if (row.toolType() == ToolType.EXTERNAL) {
                    if (!compact) {
                        Map<String, Object> schema = row.paramsSchema().isEmpty()
                                ? Map.of("type", "object", "properties", Map.of())
                                : row.paramsSchema();
                        toolkit.registerSchema(ToolSchema.builder()
                                .name(row.toolCode())
                                .description(row.description() == null ? row.name() : row.description())
                                .parameters(schema)
                                .build());
                    }
                } else if (row.toolType() == ToolType.CUSTOM_INTERNAL) {
                    // 服务端 HTTP 工具：参数、超时、内网拦截都在 HttpApiTool 里
                    HttpApiTool tool = new HttpApiTool(row.toolCode(), row.name(),
                            row.description(), row.paramsSchema(), row.config(),
                            row.permissionCode());
                    if (policies.get(row.toolCode()) == ApprovalMode.ASK) {
                        if (!compact) {
                            toolkit.registerAgentTool(tool);
                        }
                        internalToolkit.registerAgentTool(tool);
                    } else if (compact) {
                        internalToolkit.registerAgentTool(tool);
                    } else {
                        toolkit.registerAgentTool(tool);
                    }
                }
            });
        }

        if (compact) {
            ToolDirectory directory = new ToolDirectory(toolRegistry, definitionRepository,
                    tenant, enabledTools, policies, internalToolkit);
            if (mode == ToolExposureMode.CLI) {
                toolkit.registerAgentTool(new ToolCliTool(directory));
            } else {
                ToolMetaTool.all(directory).forEach(toolkit::registerAgentTool);
            }
        }

        // 观测：装饰 Model 拦截实际发给模型 HTTP 接口的请求体
        //（2.0.4 的 middleware onModelCall 未接线，Model.stream 是唯一必经点）
        // 压缩摘要也走同一个 Model，因此会被一并观测到
        Model model = new RecordingModel(modelFactory.build(definition), modelCallRepository);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("baseplate-" + definition.agentCode())
                .sysPrompt(systemPrompt(definition))
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters(definition))
                .stateStore(stateStore);

        if (properties.isCompactionEnabled()) {
            // 官方自动压缩在每轮 onReasoning 前按 token 阈值触发：
            // 支持工具结果裁剪 / 参数截断，并把摘要直接写回 AgentState，
            // 由 ReActAgent 自己的 saveStateToSession 做 CAS 持久化。
            // 平台没有 harness 工作区，flush/offload 已在配置里关闭，WorkspaceManager 传 null。
            builder.middlewares(List.of(new CompactionMiddleware(
                    null, model, AgentCompactionConfig.automatic(properties, definition))));
        }

        // HITL：把配置为 ASK 的工具登记为需确认；DENY 已在上面的 enabledTools 里排除。
        // 其余放行工具必须显式登记 ALLOW，否则会落到 PermissionEngine 的默认 ASK。
        // 入口工具不参与业务策略，同样要补 ALLOW，否则有 ASK 工具时会被默认值拦成审批。
        PermissionContextState permission = permissionContext(entryPolicies(policies, mode));
        if (!permission.isTrivial()) {
            builder.permissionContext(permission);
        }
        // 技能正文在本地目录；目录为空时不挂载，避免框架对空仓库直接报错
        if (skillIndexService.available()) {
            builder.skillRepository(skillIndexService.repository());
            List<String> effectiveSkills = effectiveSkills(skillNames, mode);
            if (effectiveSkills != null) {
                // 绑定为空 = 这个 Agent 明确不使用业务技能；null 才表示未配置、全部可用
                builder.skillFilter(effectiveSkills.isEmpty()
                        ? SkillFilter.none()
                        : SkillFilter.only(effectiveSkills.toArray(String[]::new)));
            }
        }
        return builder.build();
    }

    private List<String> skillsForMode(ToolExposureMode mode) {
        if (!properties.isToolExposureSkill()) {
            return List.of();
        }
        return switch (mode) {
            case CLI -> skillIndexService.byMode(SKILL_MODE_CLI);
            case META -> skillIndexService.byMode(SKILL_MODE_META);
            case DIRECT -> List.of();
        };
    }

    private List<String> effectiveSkills(List<String> bound, ToolExposureMode mode) {
        List<String> modeSkills = skillsForMode(mode);
        return modeSkills.isEmpty() ? bound : modeSkills;
    }

    public static Map<String, ApprovalMode> entryPolicies(Map<String, ApprovalMode> policies,
                                                          ToolExposureMode mode) {
        Map<String, ApprovalMode> effective = new LinkedHashMap<>(policies);
        if (mode != ToolExposureMode.DIRECT) {
            List<String> entries = mode == ToolExposureMode.CLI
                    ? List.of(ToolCliTool.CODE) : ToolMetaTool.codes();
            for (String code : entries) {
                // 入口工具只是分发器，自身一律 ALLOW；内部工具仍按各自策略在 ToolDirectory 二次校验
                effective.put(code, ApprovalMode.ALLOW);
            }
        }
        effective.putIfAbsent(SKILL_LOAD_TOOL, ApprovalMode.ALLOW);
        return effective;
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
     * 系统提示词 = 配置内容 + 通用运行规则。
     *
     * <p>不拼接 pageKey、可用工具列表这类每轮可能变化的信息：系统提示词是请求的固定前缀，
     * 前缀不稳定就无法命中厂商侧的 prompt 缓存。页面范围只在入口校验，不进入提示词。</p>
     *
     * <p>public 供观测模块复用：详情回放与导出要展示「实际发送给模型的 system prompt」，
     * 而不是数据库里的原始配置，两边口径必须一致。</p>
     */
    public String systemPrompt(AgentDefinition definition) {
        String base = definition.systemPrompt() == null || definition.systemPrompt().isBlank()
                ? DEFAULT_SYSTEM_PROMPT : definition.systemPrompt();
        String entryRules = switch (properties.getToolExposureMode()) {
            case CLI -> entryRule("baseplate 命令行", "find / show / call", "call");
            case META -> entryRule("三个元工具", "tool_search / tool_describe / tool_call",
                    "tool_call");
            case DIRECT -> "只调用提供给你的工具；不确定参数时先查询再回答。";
        };
        return base + "\n\n[运行规则]\n" + entryRules + "\n"
                + "工具返回失败时必须如实告知用户失败原因，绝不能声称成功。";
    }

    private int maxIters(AgentDefinition definition) {
        Object configured = definition.modelParams() == null
                ? null : definition.modelParams().get("maxIters");
        return resolveMaxIters(configured, properties.getMaxIters());
    }

    /** 负数表示无限循环；其余夹在 1..1000，避免配置异常把线程拖死。 */
    static int resolveMaxIters(Object configured, int fallback) {
        int value = configured instanceof Number number ? number.intValue() : fallback;
        if (value < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, Math.min(value, 1000));
    }

    private String entryRule(String channel, String tools, String entryName) {
        return "所有业务操作都通过" + channel + "（" + tools + "）完成；外部工具与需审批工具"
                + "同样用 " + entryName + " 执行，入口会自动请求浏览器执行或人工确认，"
                + "不要尝试直接调用工具名。";
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
