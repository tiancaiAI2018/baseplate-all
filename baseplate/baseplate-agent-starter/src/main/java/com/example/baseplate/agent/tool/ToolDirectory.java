package com.example.baseplate.agent.tool;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.agent.runtime.UiAwareToolResultConverter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具目录内核：CLI 与元工具共用同一份检索与执行路由。
 *
 * <p>只暴露「当前 Agent、当前页面、当前用户」真正可用的工具，避免模型看到用不了的工具后
 * 反复尝试。执行统一走影子 {@link Toolkit}，不经过 Toolkit 的激活门，
 * 但权限、页面、审批策略在这里显式校验一次，不依赖模型自觉。</p>
 */
public final class ToolDirectory {

    private final ToolRegistry toolRegistry;
    private final AgentDefinitionRepository definitions;
    private final String tenant;
    private final Set<String> enabledTools;
    private final Map<String, ApprovalMode> policies;
    private final Toolkit internalToolkit;
    private final Map<String, AgentDefinitionRepository.ToolCatalogRow> dbTools;

    public ToolDirectory(ToolRegistry toolRegistry, AgentDefinitionRepository definitions,
                         String tenant, Set<String> enabledTools,
                         Map<String, ApprovalMode> policies, Toolkit internalToolkit) {
        this.toolRegistry = toolRegistry;
        this.definitions = definitions;
        this.tenant = tenant;
        this.enabledTools = enabledTools;
        this.policies = policies;
        this.internalToolkit = internalToolkit;
        this.dbTools = loadDbTools();
    }

    // 目录会被多次检索，数据库工具在构造时一次查完，避免每次 find 都打库
    private Map<String, AgentDefinitionRepository.ToolCatalogRow> loadDbTools() {
        Map<String, AgentDefinitionRepository.ToolCatalogRow> loaded = new HashMap<>();
        for (String code : enabledTools) {
            if (toolRegistry.find(code) != null) {
                continue;
            }
            definitions.findTool(tenant, code).ifPresent(row -> loaded.put(code, row));
        }
        return loaded;
    }

    /** 目录条目。{@code blocked} 为 null 表示可被入口工具执行，否则是不可用原因。 */
    public record Entry(String code, String name, String description, ToolType type,
                        boolean builtin, Set<String> pages, String permissionCode,
                        ApprovalMode approval, Map<String, Object> schema, String blocked) {

        public boolean callable() {
            return blocked == null;
        }
    }

    /**
     * 按关键字检索。空关键字返回全部；code 命中权重高于描述，便于模型用英文前缀快速定位。
     */
    public List<Entry> search(String query, String type, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> tokens = normalized.isBlank() ? List.of() : List.of(normalized.split("\\s+"));
        int max = Math.max(1, Math.min(50, limit <= 0 ? 20 : limit));

        List<Entry> matched = new ArrayList<>();
        for (Entry entry : candidates()) {
            if (!typeMatches(entry, type)) {
                continue;
            }
            if (tokens.isEmpty() || score(entry, tokens) > 0) {
                matched.add(entry);
            }
        }
        matched.sort(Comparator
                .comparingInt((Entry entry) -> -score(entry, tokens))
                .thenComparing(Entry::code));
        return matched.size() > max ? matched.subList(0, max) : matched;
    }

    public Optional<Entry> describe(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return candidates().stream().filter(entry -> entry.code().equals(code)).findFirst();
    }

    public boolean requiresApproval(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        Entry entry = describe(code).orElse(null);
        return entry != null && entry.approval() == ApprovalMode.ASK;
    }

    /** 用户确认后重放入口脚本时，ASK 工具已获授权，可以执行。 */
    public Mono<ToolResultBlock> invoke(Entry entry, Map<String, Object> args, RuntimeContext context) {
        return invoke(entry, args, context, false);
    }

    /** 执行一个条目。参数与 RuntimeContext 原样透传，保证内部工具仍能拿到 principal/tenant。 */
    public Mono<ToolResultBlock> invoke(Entry entry, Map<String, Object> args, RuntimeContext context,
                                        boolean approved) {
        if (entry == null) {
            return Mono.just(ToolResultBlock.error("工具不存在"));
        }
        if (entry.blocked() != null
                && !(approved && entry.approval() == ApprovalMode.ASK)) {
            return Mono.just(ToolResultBlock.error(entry.code() + " 不可用: " + entry.blocked()));
        }
        CurrentUser user = context == null ? null : context.get("principal");
        if (!hasPermission(user, entry.permissionCode())) {
            return Mono.just(ToolResultBlock.error("当前用户缺少权限：" + entry.permissionCode()));
        }
        String pageKey = context == null ? null : context.get("pageKey");
        if (!allowedOnPage(entry, pageKey)) {
            return Mono.just(ToolResultBlock.error(entry.code() + " 不能在当前页面使用"));
        }
        AgentTool tool = internalToolkit.getTool(entry.code());
        if (tool == null) {
            return Mono.just(ToolResultBlock.error("工具未注册到执行器: " + entry.code()));
        }
        Map<String, Object> input = args == null ? Map.of() : args;
        ToolUseBlock use = ToolUseBlock.builder()
                .id("cli-" + entry.code() + "-" + System.nanoTime())
                .name(entry.code())
                .input(input)
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(use)
                .input(input)
                .runtimeContext(context)
                .build();
        return tool.callAsync(param);
    }

    public static final String ASK_KEY = "__baseplate_ask__";
    public static final String APPROVED_KEY = "__baseplate_approved__";

    public static PermissionDecision askDecision(String code) {
        return PermissionDecision.builder()
                .behavior(PermissionBehavior.ASK)
                .message("工具 " + code + " 需要人工确认后执行")
                .decisionReason("safety: " + code + " 配置为需人工确认")
                .build();
    }

    /** 把内部工具的 ui 卡片挂到入口工具结果上，否则前端收不到 SSE ui 事件。 */
    public static ToolResultBlock withUi(String text, Object ui) {
        TextBlock body = TextBlock.builder().text(text == null ? "" : text).build();
        if (!(ui instanceof Map<?, ?> card)) {
            return ToolResultBlock.of(List.of(body));
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        card.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return ToolResultBlock.of(body,
                Map.of(UiAwareToolResultConverter.UI_METADATA_KEY, copy));
    }

    public static Object uiOf(ToolResultBlock block) {
        if (block == null || block.getMetadata() == null) {
            return null;
        }
        return block.getMetadata().get(UiAwareToolResultConverter.UI_METADATA_KEY);
    }

    /** 把工具结果压成纯文本，供 CLI 直接回给模型。 */
    public static String textOf(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock content : block.getOutput()) {
            if (content instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            } else {
                text.append(content);
            }
        }
        return text.toString();
    }

    private List<Entry> candidates() {
        List<Entry> entries = new ArrayList<>();
        for (String code : enabledTools) {
            AgentToolSpec spec = toolRegistry.find(code);
            if (spec != null) {
                ToolDescriptor descriptor = spec.descriptor();
                entries.add(new Entry(descriptor.code(), descriptor.name(),
                        descriptor.description(), descriptor.type(), descriptor.builtin(),
                        descriptor.pages(), descriptor.permissionCode(), approval(code),
                        schemaOf(descriptor.code(), descriptor.paramsSchema()),
                        blocker(descriptor, code)));
                continue;
            }
            AgentDefinitionRepository.ToolCatalogRow row = dbTools.get(code);
            if (row != null) {
                entries.add(new Entry(row.toolCode(), row.name(), row.description(), row.toolType(),
                        row.builtin(), row.pages(), row.permissionCode(), approval(code),
                        schemaOf(row.toolCode(), row.paramsSchema()), blocker(row, code)));
            }
        }
        return entries;
    }

    /** 内置工具的参数由 @Tool 方法反射生成，代码里声明的 schema 通常为空。 */
    private Map<String, Object> schemaOf(String code, Map<String, Object> fallback) {
        AgentTool tool = internalToolkit.getTool(code);
        if (tool != null && tool.getParameters() != null && !tool.getParameters().isEmpty()) {
            return tool.getParameters();
        }
        return fallback == null ? Map.of() : fallback;
    }

    private ApprovalMode approval(String code) {
        return policies.getOrDefault(code, ApprovalMode.ALLOW);
    }

    private String blocker(ToolDescriptor descriptor, String code) {
        if (descriptor.type() == ToolType.EXTERNAL) {
            return "外部工具由浏览器执行，用 CLI call 执行即可自动请求前端处理";
        }
        if (approval(code) == ApprovalMode.ASK) {
            return "该工具需要人工确认，用 CLI call 执行即可自动弹出审批单";
        }
        return null;
    }

    private String blocker(AgentDefinitionRepository.ToolCatalogRow row, String code) {
        if (row.toolType() == ToolType.EXTERNAL) {
            return "外部工具由浏览器执行，用 CLI call 执行即可自动请求前端处理";
        }
        if (approval(code) == ApprovalMode.ASK) {
            return "该工具需要人工确认，用 CLI call 执行即可自动弹出审批单";
        }
        return null;
    }

    private static boolean typeMatches(Entry entry, String type) {
        if (type == null || type.isBlank() || "all".equalsIgnoreCase(type)) {
            return true;
        }
        if ("external".equalsIgnoreCase(type)) {
            return entry.type() == ToolType.EXTERNAL;
        }
        if ("internal".equalsIgnoreCase(type)) {
            return entry.type() != ToolType.EXTERNAL;
        }
        return true;
    }

    private static int score(Entry entry, List<String> tokens) {
        if (tokens.isEmpty()) {
            return 1;
        }
        String code = entry.code().toLowerCase(Locale.ROOT);
        String name = entry.name() == null ? "" : entry.name().toLowerCase(Locale.ROOT);
        String description = entry.description() == null
                ? "" : entry.description().toLowerCase(Locale.ROOT);
        int total = 0;
        for (String token : tokens) {
            int value = 0;
            if (code.startsWith(token)) {
                value = 4;
            } else if (code.contains(token)) {
                value = 3;
            } else if (name.contains(token)) {
                value = 2;
            } else if (description.contains(token)) {
                value = 1;
            }
            if (value == 0) {
                return 0;
            }
            total += value;
        }
        return total;
    }

    private static boolean allowedOnPage(Entry entry, String pageKey) {
        Set<String> pages = entry.pages();
        return pages == null || pages.isEmpty() || pages.contains("*")
                || (pageKey != null && pages.contains(pageKey));
    }

    private static boolean hasPermission(CurrentUser user, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return true;
        }
        if (user == null) {
            return false;
        }
        if (user.roles() != null && user.roles().contains("SUPER_ADMIN")) {
            return true;
        }
        return user.permissions() != null && user.permissions().contains(permissionCode);
    }
}
