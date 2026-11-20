package com.example.baseplate.cend.application;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.metadata.application.MetadataService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class CendAgentTools {

    private static final int MAX_DSL_LENGTH = 8000;

    private final MetadataService metadataService;
    private final ObjectMapper mapper = new ObjectMapper();

    public CendAgentTools(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Tool(name = "cend_page_list",
            description = "列出当前租户已保存的 C 端页面（编码、名称、版本、状态）。"
                    + "用户问「有哪些 C 端页面」或要修改某个已有页面时先调用。")
    public String listPages(RuntimeContext ctx) {
        requirePermission(ctx, "cend:view");
        String tenant = ctx.get("tenantId");
        List<Map<String, Object>> items = metadataService.list(tenant, "CEND_PAGE").stream()
                .limit(30)
                .map(row -> Map.<String, Object>of(
                        "pageCode", row.code(),
                        "name", row.name() == null ? "" : row.name(),
                        "version", row.version(),
                        "status", row.status()))
                .toList();
        return mapper.writeValueAsString(Map.of(
                "summary", "共 " + items.size() + " 个 C 端页面",
                "items", items));
    }

    @Tool(name = "cend_page_get_draft",
            description = "读取 C 端页面最新版本的主题、画布和区块结构。"
                    + "用户要求修改已有页面时，先调用它拿到现有 DSL，再生成完整参数。")
    public String getPage(RuntimeContext ctx,
                          @ToolParam(name = "pageCode", description = "页面编码") String pageCode) {
        requirePermission(ctx, "cend:view");
        String tenant = ctx.get("tenantId");
        MetadataService.SchemaView view = metadataService.latest(tenant, "CEND_PAGE", pageCode);
        return mapper.writeValueAsString(Map.of(
                "pageCode", view.code(),
                "name", view.name() == null ? "" : view.name(),
                "version", view.version(),
                "status", view.status(),
                "schema", truncate(view.schemaJson()),
                "uiSchema", truncate(view.uiSchemaJson())));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DSL_LENGTH ? text : text.substring(0, MAX_DSL_LENGTH) + "...(已截断)";
    }

    private static void requirePermission(RuntimeContext ctx, String permission) {
        CurrentUser user = ctx.get("principal");
        if (user == null) {
            throw new IllegalStateException("缺少用户上下文");
        }
        if (user.roles() != null && user.roles().contains("SUPER_ADMIN")) {
            return;
        }
        if (user.permissions() == null || !user.permissions().contains(permission)) {
            throw new IllegalStateException("当前用户缺少权限：" + permission);
        }
    }
}
