package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentSessionRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository.CustomToolInput;
import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository.DefinitionInput;
import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository.ToolBinding;
import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository.ToolCatalogRow;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.agent.tool.HttpApiTool;
import com.example.baseplate.agent.tool.ToolDescriptor;
import com.example.baseplate.agent.tool.ToolRegistry;
import com.example.baseplate.api.exception.BaseplateException;
import com.example.baseplate.runtime.crypto.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 配置用例：CRUD、工具策略、默认 Agent 种子。
 *
 * <p>API Key 只以密文落库；编辑时留空表示保留旧值。任何返回给页面的 DTO 都不包含密钥。</p>
 */
public final class AgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigService.class);
    private static final Set<String> PROVIDERS = Set.of(
            "OPENAI_COMPATIBLE", "DASHSCOPE", "ANTHROPIC", "GEMINI", "OLLAMA");
    private static final String DEFAULT_PROMPT = """
            你是 BASEPLATE 业务基盘的智能助手。
            回答简洁准确；涉及业务数据时先调用工具确认，不要编造。
            """;

    private final AgentDefinitionRepository repository;
    private final AgentSessionRepository sessions;
    private final TransactionTemplate transaction;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeFactory runtimeFactory;
    private final AgentProperties properties;
    private final SkillIndexService skillIndexService;

    public AgentConfigService(AgentDefinitionRepository repository, AgentSessionRepository sessions,
                              PlatformTransactionManager transactionManager,
                              ToolRegistry toolRegistry, AgentRuntimeFactory runtimeFactory,
                              AgentProperties properties, SkillIndexService skillIndexService) {
        this.repository = repository;
        this.sessions = sessions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.toolRegistry = toolRegistry;
        this.runtimeFactory = runtimeFactory;
        this.properties = properties;
        this.skillIndexService = skillIndexService;
    }

    public record DefinitionRequest(
            String agentCode,
            String name,
            String description,
            String provider,
            String modelName,
            String baseUrl,
            String apiKey,
            String systemPrompt,
            Map<String, Object> modelParams,
            List<String> allowedPages,
            String status) {
    }

    public record DefinitionView(
            long id,
            String agentCode,
            String name,
            String description,
            String provider,
            String modelName,
            String baseUrl,
            boolean apiKeyConfigured,
            String systemPrompt,
            Map<String, Object> modelParams,
            List<String> allowedPages,
            String status,
            int revision) {
    }

    public record ToolBindingRequest(String toolCode, boolean enabled, String approvalMode) {
    }

    /** 技能绑定入参：页面保存完整的勾选结果（含取消勾选）。 */
    public record SkillBindingRequest(String skillName, boolean enabled) {
    }

    /** 技能绑定视图：所有已索引技能 + 当前 Agent 是否启用。 */
    public record SkillBindingView(
            String skillName,
            String description,
            String version,
            String status,
            boolean enabled) {
    }

    public record ToolView(
            String toolCode,
            String name,
            String description,
            String toolType,
            boolean builtin,
            boolean editable,
            Set<String> pages,
            String permissionCode,
            String riskLevel,
            String minApproval,
            String defaultApproval,
            boolean enabled,
            String approvalMode,
            String effectiveApproval) {
    }

    /** 工具目录视图：内置工具只读，自定义外部工具可编辑。 */
    public record ToolDefinitionView(
            String toolCode,
            String name,
            String description,
            String toolType,
            boolean builtin,
            boolean editable,
            Set<String> pages,
            String permissionCode,
            String riskLevel,
            String minApproval,
            String defaultApproval,
            Map<String, Object> paramsSchema,
            Map<String, Object> config) {
    }

    public record ToolDefinitionRequest(
            String toolCode,
            String name,
            String description,
            String toolType,
            Set<String> pages,
            String permissionCode,
            String riskLevel,
            String minApproval,
            String defaultApproval,
            Map<String, Object> paramsSchema,
            Map<String, Object> config) {
    }

    public List<DefinitionView> list(String tenant) {
        return repository.list(tenant).stream().map(this::toView).toList();
    }

    public DefinitionView create(String tenant, DefinitionRequest request) {
        validate(request, true);
        DefinitionInput input = new DefinitionInput(
                request.agentCode(), request.name(), request.description(),
                normalizeProvider(request.provider()), request.modelName().trim(), request.baseUrl(),
                encryptIfPresent(request.apiKey()),
                request.systemPrompt(), safeParams(request.modelParams()),
                normalizePages(request.allowedPages()),
                request.status() == null ? "ENABLED" : request.status());
        long id = repository.insert(tenant, input);
        // 新 Agent 默认开放内置工具，但只写入页面兼容的那部分：
        // 给一个 dashboard 的 Agent 绑定流程设计器工具只会制造永远用不上的配置。
        for (ToolDescriptor descriptor : toolRegistry.descriptors()) {
            if (!pageCompatible(descriptor.pages(), input.allowedPages())) {
                continue;
            }
            repository.upsertToolBinding(tenant, id, descriptor.code(), true, ApprovalMode.INHERIT);
        }
        runtimeFactory.invalidate(id);
        return repository.find(tenant, id).map(this::toView)
                .orElseThrow(() -> new BaseplateException("INTERNAL_ERROR", "智能体创建失败"));
    }

    public DefinitionView update(String tenant, long id, DefinitionRequest request) {
        validate(request, false);
        AgentDefinition existing = repository.find(tenant, id)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
        DefinitionInput input = new DefinitionInput(
                existing.agentCode(), request.name(), request.description(),
                normalizeProvider(request.provider()), request.modelName().trim(), request.baseUrl(),
                // 留空保持旧密钥；只有明确输入新值才替换
                encryptIfPresent(request.apiKey()),
                request.systemPrompt(), safeParams(request.modelParams()),
                normalizePages(request.allowedPages()),
                request.status() == null ? existing.status() : request.status());
        repository.update(tenant, id, input);
        runtimeFactory.invalidate(id);
        return repository.find(tenant, id).map(this::toView)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
    }

    public void delete(String tenant, long id) {
        transaction.executeWithoutResult(status -> {
            repository.deleteDependents(tenant, id);
            sessions.deleteByAgent(tenant, id);
            repository.delete(tenant, id);
        });
        runtimeFactory.invalidate(id);
    }

    /** 工具目录 + 该 Agent 的启停/审批策略 + 最终生效策略。 */
    public List<ToolView> tools(String tenant, long agentId) {
        AgentDefinition agent = repository.find(tenant, agentId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
        Map<String, ToolBinding> bindings = repository.toolBindings(tenant, agentId).stream()
                .collect(Collectors.toMap(ToolBinding::toolCode, Function.identity()));
        List<ToolView> views = new ArrayList<>();
        for (ToolCatalogRow row : repository.toolCatalog(tenant)) {
            // 只展示与 Agent 页面有交集的工具：跨页面工具既调不到，也不该出现在配置页
            if (!pageCompatible(row.pages(), agent.allowedPages())) {
                continue;
            }
            ToolBinding binding = bindings.get(row.toolCode());
            boolean enabled = binding == null ? row.builtin() : binding.enabled();
            ApprovalMode configured = binding == null ? ApprovalMode.INHERIT : binding.approvalMode();
            ApprovalMode effective = ApprovalMode.effective(configured, row.defaultApproval(),
                    row.minApproval());
            views.add(new ToolView(row.toolCode(), row.name(), row.description(),
                    row.toolType().name(), row.builtin(), row.editable(), row.pages(),
                    row.permissionCode(), row.riskLevel().name(), row.minApproval().name(),
                    row.defaultApproval().name(), enabled, configured.name(), effective.name()));
        }
        return views;
    }

    /** 技能绑定：未配置过时默认全部启用，避免新表上线后存量 Agent 突然失去技能。 */
    public List<SkillBindingView> skills(String tenant, long agentId) {
        requireAgent(tenant, agentId);
        List<AgentDefinitionRepository.SkillBinding> bindings =
                repository.skillBindings(tenant, agentId);
        Map<String, Boolean> enabled = bindings.stream()
                .collect(Collectors.toMap(AgentDefinitionRepository.SkillBinding::skillName,
                        AgentDefinitionRepository.SkillBinding::enabled, (left, right) -> left));
        boolean configured = !bindings.isEmpty();
        return skillIndexService.list(tenant).stream()
                .map(skill -> new SkillBindingView(skill.skillName(), skill.description(),
                        skill.version(), skill.status(),
                        configured ? Boolean.TRUE.equals(enabled.get(skill.skillName())) : true))
                .toList();
    }

    /** 全量保存技能勾选结果；取消全部勾选是合法配置，表示该 Agent 不使用技能。 */
    public void saveSkills(String tenant, long agentId, List<SkillBindingRequest> request) {
        requireAgent(tenant, agentId);
        List<AgentDefinitionRepository.SkillBinding> bindings = request == null ? List.of()
                : request.stream()
                    .filter(item -> item.skillName() != null && !item.skillName().isBlank())
                    .map(item -> new AgentDefinitionRepository.SkillBinding(
                            item.skillName().trim(), item.enabled()))
                    .toList();
        repository.replaceSkillBindings(tenant, agentId, bindings);
        runtimeFactory.invalidate(agentId);
    }

    private void requireAgent(String tenant, long agentId) {
        repository.find(tenant, agentId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
    }

    /** 工具页面与 Agent 页面有交集才兼容；任意一侧为 * 或空表示不限。 */
    private static boolean pageCompatible(Set<String> toolPages, List<String> agentPages) {
        if (toolPages == null || toolPages.isEmpty() || toolPages.contains("*")) {
            return true;
        }
        if (agentPages == null || agentPages.isEmpty() || agentPages.contains("*")) {
            return true;
        }
        return toolPages.stream().anyMatch(agentPages::contains);
    }

    public List<ToolDefinitionView> toolDirectory(String tenant) {
        return repository.toolCatalog(tenant).stream().map(row -> new ToolDefinitionView(
                row.toolCode(), row.name(), row.description(), row.toolType().name(),
                row.builtin(), row.editable(), row.pages(), row.permissionCode(),
                row.riskLevel().name(), row.minApproval().name(), row.defaultApproval().name(),
                row.paramsSchema(), row.config())).toList();
    }

    public ToolDefinitionView createTool(String tenant, ToolDefinitionRequest request) {
        CustomToolInput input = validateTool(request);
        if (repository.findTool(tenant, input.toolCode()).isPresent()) {
            throw new BaseplateException("CONFLICT", "工具编码已存在: " + input.toolCode());
        }
        repository.insertCustomTool(tenant, input);
        return repository.findTool(tenant, input.toolCode()).map(row -> new ToolDefinitionView(
                row.toolCode(), row.name(), row.description(), row.toolType().name(),
                row.builtin(), row.editable(), row.pages(), row.permissionCode(),
                row.riskLevel().name(), row.minApproval().name(), row.defaultApproval().name(),
                row.paramsSchema(), row.config()))
                .orElseThrow(() -> new BaseplateException("INTERNAL_ERROR", "工具创建失败"));
    }

    public ToolDefinitionView updateTool(String tenant, String toolCode,
                                         ToolDefinitionRequest request) {
        ToolDefinitionRequest normalized = new ToolDefinitionRequest(toolCode, request.name(),
                request.description(), request.toolType(), request.pages(),
                request.permissionCode(), request.riskLevel(), request.minApproval(),
                request.defaultApproval(), request.paramsSchema(), request.config());
        CustomToolInput input = validateTool(normalized);
        if (!repository.updateCustomTool(tenant, toolCode, input)) {
            throw new BaseplateException("FORBIDDEN", "内置工具只读，不能修改或删除");
        }
        runtimeFactory.invalidateAll();
        return repository.findTool(tenant, toolCode).map(row -> new ToolDefinitionView(
                row.toolCode(), row.name(), row.description(), row.toolType().name(),
                row.builtin(), row.editable(), row.pages(), row.permissionCode(),
                row.riskLevel().name(), row.minApproval().name(), row.defaultApproval().name(),
                row.paramsSchema(), row.config()))
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "工具不存在"));
    }

    public void deleteTool(String tenant, String toolCode) {
        if (!repository.deleteCustomTool(tenant, toolCode)) {
            throw new BaseplateException("FORBIDDEN", "内置工具只读，不能修改或删除");
        }
        runtimeFactory.invalidateAll();
    }

    private CustomToolInput validateTool(ToolDefinitionRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (request.pages() == null || request.pages().isEmpty()) {
            throw new IllegalArgumentException("至少绑定一个页面");
        }
        String code = request.toolCode();
        if (code == null || !code.matches("[a-z][a-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("工具编码只能由小写字母、数字、下划线组成，长度 3-64");
        }
        ToolType type;
        try {
            type = ToolType.valueOf(request.toolType() == null
                    ? "EXTERNAL" : request.toolType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的工具类型: " + request.toolType());
        }
        if (type != ToolType.EXTERNAL && type != ToolType.CUSTOM_INTERNAL) {
            throw new IllegalArgumentException("不支持的工具类型: " + type);
        }
        Map<String, Object> config = request.config() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.config());
        if (type == ToolType.CUSTOM_INTERNAL) {
            HttpApiTool.validateUrl(String.valueOf(config.get("url")));
            String method = config.get("method") == null
                    ? "POST" : String.valueOf(config.get("method")).toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"POST".equals(method)) {
                throw new IllegalArgumentException("内部 HTTP 工具只支持 GET/POST");
            }
            config.put("method", method);
        }
        RiskLevel risk = parseEnum(RiskLevel.class, request.riskLevel(), RiskLevel.WRITE, "风险级别");
        ApprovalMode min = parseEnum(ApprovalMode.class, request.minApproval(),
                ApprovalMode.ALLOW, "安全下限");
        if (risk == RiskLevel.DESTRUCTIVE && min.strictness() < ApprovalMode.ASK.strictness()) {
            min = ApprovalMode.ASK;
        }
        ApprovalMode defaultApproval = parseEnum(ApprovalMode.class, request.defaultApproval(),
                min, "默认策略");
        defaultApproval = ApprovalMode.stricter(defaultApproval, min);

        Map<String, Object> schema = request.paramsSchema() == null || request.paramsSchema().isEmpty()
                ? Map.of("type", "object", "properties", Map.of())
                : new LinkedHashMap<>(request.paramsSchema());
        return new CustomToolInput(code, request.name().trim(), request.description(), type,
                request.pages(), request.permissionCode(), risk, min, defaultApproval,
                schema, config);
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value, T fallback, String label) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法的" + label + ": " + value);
        }
    }

    public void saveTools(String tenant, long agentId, List<ToolBindingRequest> requests) {
        repository.find(tenant, agentId)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "智能体不存在"));
        Set<String> known = repository.toolCatalog(tenant).stream()
                .map(ToolCatalogRow::toolCode).collect(Collectors.toSet());
        for (ToolBindingRequest request : requests) {
            if (!known.contains(request.toolCode())) {
                throw new IllegalArgumentException("未知工具: " + request.toolCode());
            }
            ApprovalMode mode;
            try {
                mode = ApprovalMode.valueOf(request.approvalMode() == null
                        ? "INHERIT" : request.approvalMode().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("非法审批策略: " + request.approvalMode());
            }
            repository.upsertToolBinding(tenant, agentId, request.toolCode(),
                    request.enabled(), mode);
        }
        runtimeFactory.invalidate(agentId);
    }

    /**
     * 启动期种子：默认租户没有任何 Agent 时，用 .env 的模型配置建一个。
     * 这样首次启动就能在悬浮框里对话，不需要先去配置页。
     */
    public void seedDefaultIfAbsent() {
        if (!repository.list("default").isEmpty()) {
            return;
        }
        if (properties.getDefaultApiKey() == null || properties.getDefaultApiKey().isBlank()) {
            log.info("未配置 BASEPLATE_AGENT_API_KEY，跳过默认智能体种子");
            return;
        }
        String encryptedKey = AesGcmCipher.encrypt(properties.getDefaultApiKey(),
                properties.getSecretKey());
        long id = repository.insert("default", new DefinitionInput(
                "default-assistant", "默认助手",
                "由环境变量初始化的默认智能体，可在控制台修改模型与提示词。",
                "OPENAI_COMPATIBLE", properties.getDefaultModel(), properties.getDefaultBaseUrl(),
                encryptedKey, DEFAULT_PROMPT,
                Map.of("contextWindow", properties.getContextWindow(),
                        "maxOutputTokens", properties.getMaxOutputTokens(),
                        "reasoningEffort", "low"),
                List.of("*"), "ENABLED"));
        for (ToolDescriptor descriptor : toolRegistry.descriptors()) {
            repository.upsertToolBinding("default", id, descriptor.code(), true, ApprovalMode.INHERIT);
        }
        log.info("已创建默认智能体 default-assistant（模型 {}）", properties.getDefaultModel());
    }

    private void validate(DefinitionRequest request, boolean creating) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        if (request.allowedPages() == null || request.allowedPages().isEmpty()) {
            throw new IllegalArgumentException("至少绑定一个页面；全部页面请填 *");
        }
        if (creating) {
            String code = request.agentCode();
            if (code == null || !code.matches("[a-z0-9][a-z0-9-_]{1,63}")) {
                throw new IllegalArgumentException("agentCode 只能由小写字母、数字、-、_ 组成，长度 2-64");
            }
        }
    }

    private String normalizeProvider(String provider) {
        String value = provider == null ? "OPENAI_COMPATIBLE" : provider.toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(value)) {
            throw new IllegalArgumentException("不支持的模型厂商: " + provider);
        }
        return value;
    }

    private List<String> normalizePages(List<String> pages) {
        return new ArrayList<>(pages.stream()
                .filter(page -> page != null && !page.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private Map<String, Object> safeParams(Map<String, Object> params) {
        return params == null ? Map.of() : new LinkedHashMap<>(params);
    }

    private String encryptIfPresent(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return AesGcmCipher.encrypt(apiKey.trim(), properties.getSecretKey());
    }

    private DefinitionView toView(AgentDefinition definition) {
        return new DefinitionView(definition.id(), definition.agentCode(), definition.name(),
                definition.description(), definition.provider(), definition.modelName(),
                definition.baseUrl(), definition.hasApiKey(), definition.systemPrompt(),
                definition.modelParams(), definition.allowedPages(), definition.status(),
                definition.revision());
    }
}
