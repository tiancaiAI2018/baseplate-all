package com.example.baseplate.agent.tool;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.ToolType;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

/**
 * CLI 模式下的唯一常驻入口工具：把工具调用收敛成一个类似命令行的脚本。
 *
 * <p>模型不再需要为每个工具携带 schema，只需知道 find/show/call 三个子命令；
 * 真正的工具集合由 {@link ToolDirectory} 按当前 Agent、页面、权限过滤后提供，
 * 因此页面里新增到数据库的工具无需改代码即可被发现。</p>
 */
public final class ToolCliTool extends ToolBase {

    public static final String CODE = "baseplate";

    private static final int MAX_OUTPUT = 12000;
    private static final int MAX_DESCRIPTION = 80;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final ToolDirectory directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolCliTool(ToolDirectory directory) {
        super(ToolBase.builder()
                .name(CODE)
                .description("""
                        业务工具命令行。所有业务查询与操作都必须通过它完成，不要臆造工具名。
                        脚本每行一条命令，按顺序执行：
                        find <关键字> [--limit N] [--type internal|external]  检索可用工具
                        show <toolCode>                                       查看工具参数
                        call <toolCode> {json参数}                             执行工具
                        示例：
                        find workflow 流程
                        show workflow_task_detail
                        call workflow_task_detail {"instanceId": 12}
                         不确定 toolCode 时先 find 再 show。外部工具与需审批工具同样用 call 执行，入口会自动请求浏览器处理或弹出审批单。""")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "script", Map.of(
                                        "type", "string",
                                        "description", "命令行脚本，每行一条 find/show/call 命令")),
                        "required", List.of("script")))
                .readOnly(false)
                .concurrencySafe(true));
        this.directory = directory;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
                                                     PermissionContextState context) {
        Map<String, Object> ask = askRequest(toolInput);
        if (ask == null) {
            return Mono.just(PermissionDecision.passthrough(""));
        }
        markAsk(toolInput, ask);
        return Mono.just(ToolDirectory.askDecision(String.valueOf(ask.get("code"))));
    }

    private Map<String, Object> askRequest(Map<String, Object> input) {
        Object raw = input == null ? null : input.get("script");
        if (!(raw instanceof String script)) {
            return null;
        }
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("call ") && !trimmed.startsWith("invoke ")) {
                continue;
            }
            String rest = trimmed.substring(trimmed.indexOf(' ') + 1).trim();
            int split = rest.indexOf(' ');
            String code = split < 0 ? rest : rest.substring(0, split);
            if (!directory.requiresApproval(code)) {
                continue;
            }
            Map<String, Object> args = Map.of();
            if (split >= 0) {
                try {
                    args = parseArgs(rest.substring(split + 1).trim());
                } catch (RuntimeException ignored) {
                }
            }
            return Map.of("code", code, "args", args);
        }
        return null;
    }

    private static void markAsk(Map<String, Object> input, Map<String, Object> ask) {
        if (input == null) {
            return;
        }
        try {
            input.put(ToolDirectory.ASK_KEY, ask);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String script = string(param.getInput() == null ? null : param.getInput().get("script"));
        return Mono.fromCallable(() -> run(script, param))
                .map(result -> ToolDirectory.withUi(result.text(), result.ui()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private CliResult run(String script, ToolCallParam param) {
        if (script == null || script.isBlank()) {
            return CliResult.of(usage());
        }
        StringBuilder output = new StringBuilder();
        Object ui = null;
        int executed = 0;
        for (String raw : script.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (executed++ > 0) {
                output.append('\n');
            }
            CliResult result = execute(line, param);
            output.append(result.text());
            if (result.ui() != null) {
                ui = result.ui();
            }
            if (output.length() > MAX_OUTPUT) {
                return CliResult.of(output.substring(0, MAX_OUTPUT)
                        + "\n… 输出超过上限，请缩小 find 或参数", ui);
            }
        }
        return CliResult.of(executed == 0 ? usage() : output.toString(), ui);
    }

    private CliResult execute(String line, ToolCallParam param) {
        int split = line.indexOf(' ');
        String command = (split < 0 ? line : line.substring(0, split)).toLowerCase(Locale.ROOT);
        String rest = split < 0 ? "" : line.substring(split + 1).trim();
        try {
            return switch (command) {
                case "find", "ls", "search" -> CliResult.of(find(rest));
                case "show", "describe" -> CliResult.of(show(rest));
                case "call", "invoke" -> call(rest, param);
                default -> CliResult.of("! 未知命令 " + command + "，可用命令: find / show / call");
            };
        } catch (ToolSuspendException e) {
            throw e;
        } catch (RuntimeException e) {
            return CliResult.of("! " + command + " 失败: " + e.getMessage());
        }
    }

    private String find(String rest) {
        int limit = 20;
        String type = "all";
        String query = rest;
        String[] parts = rest.split("\\s+");
        StringBuilder remaining = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if ("--limit".equals(part) && index + 1 < parts.length) {
                limit = parseInt(parts[++index], 20);
            } else if ("--type".equals(part) && index + 1 < parts.length) {
                type = parts[++index];
            } else if (part.startsWith("--limit=")) {
                limit = parseInt(part.substring("--limit=".length()), 20);
            } else if (part.startsWith("--type=")) {
                type = part.substring("--type=".length());
            } else if (!part.isBlank()) {
                remaining.append(remaining.isEmpty() ? "" : " ").append(part);
            }
        }
        query = remaining.toString();

        List<ToolDirectory.Entry> entries = directory.search(query, type, limit);
        if (entries.isEmpty()) {
            return "没有匹配的工具，换个关键字或直接 find 查看全部。";
        }
        StringBuilder text = new StringBuilder();
        text.append(entries.size()).append(" 个工具");
        for (ToolDirectory.Entry entry : entries) {
            text.append('\n').append(render(entry));
        }
        text.append("\n用 show <code> 查看参数，用 call <code> {json} 执行");
        return text.toString();
    }

    private String show(String code) {
        Optional<ToolDirectory.Entry> found = directory.describe(code);
        if (found.isEmpty()) {
            return "! 工具不存在或当前不可用: " + code + "，先用 find 确认 code";
        }
        ToolDirectory.Entry entry = found.get();
        StringBuilder text = new StringBuilder();
        text.append(entry.code()).append(" | ").append(typeName(entry))
                .append(" | approval=").append(entry.approval());
        if (entry.permissionCode() != null && !entry.permissionCode().isBlank()) {
            text.append(" | permission=").append(entry.permissionCode());
        }
        if (entry.blocked() != null) {
            text.append("\n[不可执行] ").append(entry.blocked());
        }
        text.append('\n').append(oneLine(entry.description()));
        Map<String, Object> schema = entry.schema();
        text.append("\n参数: ").append(schema == null || schema.isEmpty()
                ? "无（call " + entry.code() + "）"
                : mapper.writeValueAsString(schema));
        return text.toString();
    }

    private CliResult call(String rest, ToolCallParam param) {
        int split = rest.indexOf(' ');
        String code = split < 0 ? rest : rest.substring(0, split);
        String json = split < 0 ? "{}" : rest.substring(split + 1).trim();
        if (code.isBlank()) {
            return CliResult.of("! call 需要 toolCode，先用 find 检索");
        }
        Optional<ToolDirectory.Entry> found = directory.describe(code);
        if (found.isEmpty()) {
            return CliResult.of("! 工具不存在或当前不可用: " + code + "，先用 find 确认 code");
        }
        ToolDirectory.Entry entry = found.get();
        Map<String, Object> args = parseArgs(json);
        if (entry.type() == ToolType.EXTERNAL) {
            throw new ToolSuspendException("外部工具 " + entry.code() + " 需要浏览器执行");
        }
        boolean approved = Boolean.TRUE.equals(param.getInput().get(ToolDirectory.APPROVED_KEY));
        if (entry.blocked() != null
                && !(approved && entry.approval() == ApprovalMode.ASK)) {
            return CliResult.of("! " + entry.code() + " 不可通过 CLI 执行: " + entry.blocked());
        }
        ToolResultBlock result = directory.invoke(entry, args, param.getRuntimeContext(), approved)
                .block(CALL_TIMEOUT);
        String text = ToolDirectory.textOf(result);
        if (text == null || text.isBlank()) {
            return CliResult.of("[" + code + "] 已执行，无返回内容");
        }
        return CliResult.of("[" + code + "]\n" + text, ToolDirectory.uiOf(result));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Object parsed = mapper.readValue(json, Object.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }
        return (Map<String, Object>) map;
    }

    private static String render(ToolDirectory.Entry entry) {
        StringBuilder text = new StringBuilder();
        text.append(entry.code()).append(" | ").append(typeName(entry));
        if (entry.blocked() != null) {
            text.append(" | ").append(entry.blocked());
        }
        text.append(" | ").append(oneLine(entry.description()));
        return text.toString();
    }

    private static String typeName(ToolDirectory.Entry entry) {
        return entry.type() == null ? "unknown" : entry.type().name().toLowerCase(Locale.ROOT);
    }

    private static String oneLine(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String flat = description.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_DESCRIPTION ? flat : flat.substring(0, MAX_DESCRIPTION) + "…";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Math.max(1, Math.min(50, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String usage() {
        return """
                用法：每行一条命令
                find <关键字> [--limit N] [--type internal|external]   检索可用工具
                show <toolCode>                                        查看工具参数
                call <toolCode> {json参数}                              执行工具""";
    }

    private record CliResult(String text, Object ui) {
        static CliResult of(String text) {
            return new CliResult(text, null);
        }

        static CliResult of(String text, Object ui) {
            return new CliResult(text, ui);
        }
    }
}
