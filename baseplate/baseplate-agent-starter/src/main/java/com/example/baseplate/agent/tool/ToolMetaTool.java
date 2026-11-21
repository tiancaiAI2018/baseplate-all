package com.example.baseplate.agent.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

/**
 * META 模式入口：把工具目录拆成 search / describe / call 三个结构化元工具。
 *
 * <p>与 {@link ToolCliTool} 的区别只在入口协议——元工具用 JSON 参数，不要求模型拼命令行；
 * 检索、权限、页面与审批校验全部复用 {@link ToolDirectory}，两种模式行为一致。</p>
 */
public final class ToolMetaTool extends ToolBase {

    public static final String SEARCH = "tool_search";
    public static final String DESCRIBE = "tool_describe";
    public static final String CALL = "tool_call";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_OUTPUT = 12000;
    private static final int MAX_DESCRIPTION = 80;

    private final String toolCode;
    private final ToolDirectory directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolMetaTool(String toolCode, ToolDirectory directory) {
        super(ToolBase.builder()
                .name(toolCode)
                .description(description(toolCode))
                .inputSchema(schema(toolCode))
                .readOnly(!CALL.equals(toolCode))
                .concurrencySafe(!CALL.equals(toolCode)));
        this.toolCode = toolCode;
        this.directory = directory;
    }

    public static List<ToolMetaTool> all(ToolDirectory directory) {
        return List.of(new ToolMetaTool(SEARCH, directory),
                new ToolMetaTool(DESCRIBE, directory),
                new ToolMetaTool(CALL, directory));
    }

    public static List<String> codes() {
        return List.of(SEARCH, DESCRIBE, CALL);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> switch (toolCode) {
                    case SEARCH -> MetaResult.of(search(input));
                    case DESCRIBE -> MetaResult.of(describe(input));
                    default -> call(input, param);
                })
                .map(result -> ToolDirectory.withUi(result.text(), result.ui()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String search(Map<String, Object> input) {
        List<ToolDirectory.Entry> entries = directory.search(
                string(input.get("query")), string(input.get("type")), integer(input.get("limit"), 20));
        if (entries.isEmpty()) {
            return "没有匹配的工具，换个关键字或省略 query 查看全部。";
        }
        StringBuilder text = new StringBuilder();
        text.append(entries.size()).append(" 个工具");
        for (ToolDirectory.Entry entry : entries) {
            text.append('\n').append(render(entry));
        }
        text.append("\n用 ").append(DESCRIBE).append(" 查看参数，用 ").append(CALL).append(" 执行");
        return clip(text.toString());
    }

    private String describe(Map<String, Object> input) {
        String code = string(input.get("code"));
        Optional<ToolDirectory.Entry> found = directory.describe(code);
        if (found.isEmpty()) {
            return "! 工具不存在或当前不可用: " + code + "，先用 " + SEARCH + " 确认 code";
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
        return clip(text.toString());
    }

    private MetaResult call(Map<String, Object> input, ToolCallParam param) {
        String code = string(input.get("code"));
        Optional<ToolDirectory.Entry> found = directory.describe(code);
        if (found.isEmpty()) {
            return MetaResult.of("! 工具不存在或当前不可用: " + code + "，先用 " + SEARCH + " 确认 code");
        }
        ToolDirectory.Entry entry = found.get();
        if (entry.blocked() != null) {
            return MetaResult.of("! " + entry.code() + " 不可通过入口工具执行: " + entry.blocked());
        }
        Map<String, Object> args = arguments(input.get("arguments"));
        ToolResultBlock result = directory.invoke(entry, args, param.getRuntimeContext())
                .block(CALL_TIMEOUT);
        String text = ToolDirectory.textOf(result);
        if (text == null || text.isBlank()) {
            return MetaResult.of("[" + code + "] 已执行，无返回内容");
        }
        return MetaResult.of(clip("[" + code + "]\n" + text), ToolDirectory.uiOf(result));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String description(String code) {
        return switch (code) {
            case SEARCH -> "检索当前可用的业务工具，返回 code 与用途。不确定工具名时先调用它。";
            case DESCRIBE -> "查看指定业务工具的参数 schema、审批级别与权限点。执行前先确认参数。";
            default -> "执行业务工具。arguments 必须是 JSON 对象；外部工具与需审批工具会被拒绝并要求直接调用。";
        };
    }

    private static Map<String, Object> schema(String code) {
        return switch (code) {
            case SEARCH -> Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "关键字，可省略"),
                            "type", Map.of("type", "string", "description", "internal / external / all，默认 all"),
                            "limit", Map.of("type", "integer", "description", "最多返回条数，默认 20，上限 50")),
                    "required", List.of());
            case DESCRIBE -> Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "code", Map.of("type", "string", "description", "工具 code")),
                    "required", List.of("code"));
            default -> Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "code", Map.of("type", "string", "description", "工具 code"),
                            "arguments", Map.of("type", "object", "description", "工具参数，必须是 JSON 对象")),
                    "required", List.of("code"));
        };
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

    private static String clip(String text) {
        return text.length() <= MAX_OUTPUT ? text : text.substring(0, MAX_OUTPUT) + "\n… 输出超过上限，请缩小检索范围";
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(50, number.intValue()));
        }
        return fallback;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record MetaResult(String text, Object ui) {
        static MetaResult of(String text) {
            return new MetaResult(text, null);
        }

        static MetaResult of(String text, Object ui) {
            return new MetaResult(text, ui);
        }
    }
}
