package com.example.baseplate.agent.workflow;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.metadata.application.MetadataService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * 流程表单工具的服务端部分：只读查询。
 *
 * <p>表单设计是外部工具：模型只产出字段清单，前端负责编译成 form-create 规则、
 * 保存草稿并打开设计器，用户看得到、改得动。</p>
 */
public final class FormAgentTools {

    /** uiSchema 可能包含整段规则，超长部分对模型没有增量信息，截断避免撑爆上下文。 */
    private static final int MAX_RULE_LENGTH = 6000;

    private final MetadataService metadataService;
    private final ObjectMapper mapper = new ObjectMapper();

    public FormAgentTools(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Tool(name = "form_list_definitions",
            description = "列出当前租户已保存的表单（编码、名称、版本、状态）。"
                    + "用户问「有哪些表单」或要修改某个已有表单时先调用。")
    public String listForms(RuntimeContext ctx) {
        requirePermission(ctx, "meta:view");
        String tenant = ctx.get("tenantId");
        List<Map<String, Object>> items = metadataService.list(tenant, "FORM").stream()
                .limit(30)
                .map(row -> Map.<String, Object>of(
                        "code", row.code(),
                        "name", row.name() == null ? "" : row.name(),
                        "version", row.version(),
                        "status", row.status()))
                .toList();
        return mapper.writeValueAsString(Map.of(
                "summary", "共 " + items.size() + " 个表单",
                "items", items));
    }

    @Tool(name = "form_get_draft",
            description = "读取表单最新版本的字段结构和界面规则。"
                    + "用户要求修改已有表单时，先调用它拿到现有字段，再生成完整的字段列表。")
    public String getForm(RuntimeContext ctx,
                          @ToolParam(name = "code", description = "表单编码") String code) {
        requirePermission(ctx, "meta:view");
        // 工具在异步线程执行，没有 SecurityContext，必须用显式租户查询，
        // 否则会报「未认证」——这正是表单列表工具此前红叉的原因。
        String tenant = ctx.get("tenantId");
        MetadataService.SchemaView view = metadataService.latest(tenant, "FORM", code);
        return mapper.writeValueAsString(Map.of(
                "code", view.code(),
                "name", view.name() == null ? "" : view.name(),
                "version", view.version(),
                "schema", view.schemaJson(),
                "uiSchema", truncate(view.uiSchemaJson())));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_RULE_LENGTH ? text : text.substring(0, MAX_RULE_LENGTH) + "...(已截断)";
    }

    /** 工具执行前按当前用户权限校验；工具本身就是用户能力的延伸。 */
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
