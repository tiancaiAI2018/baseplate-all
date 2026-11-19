package com.example.baseplate.agent.workflow;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.agent.tool.AgentToolSpec;
import com.example.baseplate.agent.tool.ToolDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程表单工具声明。
 *
 * <ul>
 *   <li>查询/读取：服务端内部执行，只读。</li>
 *   <li>设计表单：外部工具，字段清单下发给前端；前端编译成 form-create 规则、
 *       保存草稿并打开设计器，模型不能声称“已发布”。</li>
 * </ul>
 *
 * <p>与工作流工具分开声明：两者页面不同、权限也不同，
 * 混在一个 provider 里会让「页面限定」和「权限过滤」互相牵连。</p>
 */
public final class FormAgentToolProvider implements AgentToolProvider {

    /** 表单设计器的路由 key，与前端 router 保持一致（列表页是 workflow/forms）。 */
    private static final Set<String> FORM_PAGES = Set.of("workflow/forms/design");

    private final FormAgentTools handler;

    public FormAgentToolProvider(FormAgentTools handler) {
        this.handler = handler;
    }

    @Override
    public List<AgentToolSpec> tools() {
        return List.of(
                new AgentToolSpec(new ToolDescriptor(
                        "form_list_definitions", "查询表单",
                        "列出已保存的流程表单。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        FORM_PAGES, "meta:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "form_get_draft", "读取表单",
                        "读取表单的字段和界面规则，供再次修改时使用。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        FORM_PAGES, "meta:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "form_get_canvas", "读取当前表单",
                        """
                        读取用户当前正在编辑的表单设计器内容（包含未保存的修改），
                        返回编码、名称、字段列表和 form-create 规则。
                        - 用户说「看看我当前这个表单」「基于当前表单改」时先调用；
                        - 修改后调用 form_create_draft 保存草稿并刷新设计器；
                        - 设计器还没初始化或读取失败时会返回原因，如实告诉用户。
                        """,
                        ToolType.EXTERNAL, true, false,
                        FORM_PAGES, "meta:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        Map.of("type", "object", "properties", Map.of())), null),
                // 外部工具：handler 为 null，保存与打开设计器都在前端完成
                new AgentToolSpec(new ToolDescriptor(
                        "form_create_draft", "设计表单",
                        """
                        根据字段列表生成表单并保存草稿，前端会打开表单设计器供用户查看和修改。
                        - 修改已有表单时 formCode 传原编码，先调用 form_get_draft 读取现有字段；
                        - 每次调用都会保存为新的草稿版本，不会自动发布；
                        - 字段类型只能使用 input/textarea/number/slider/date/datetime/switch/
                          select/radio/checkbox/upload/editor，未知类型会退化为单行文本；
                        - 字段名只能用小写字母、数字、下划线，且不能是 script/eval/expression/class 这类保留词；
                        - select/radio/checkbox 必须提供 options。
                        调用成功只代表草稿已打开，不要声称已发布；发布由用户在设计器确认。
                        如果返回失败，请根据失败原因修正参数后重新调用，不要直接结束回复。""",
                        ToolType.EXTERNAL, true, false,
                        FORM_PAGES, "meta:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        formParamsSchema()), null));
    }

    /** 模型看到的参数结构；前端按同一结构编译成 form-create 规则。 */
    private static Map<String, Object> formParamsSchema() {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("type", "object");
        option.put("properties", Map.of(
                "label", Map.of("type", "string", "description", "选项文案"),
                "value", Map.of("type", "string", "description", "选项值")));
        option.put("required", List.of("label", "value"));

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "object");
        field.put("properties", Map.of(
                "field", Map.of("type", "string",
                        "description", "字段名，英文小写+下划线，如 leave_days"),
                "title", Map.of("type", "string", "description", "字段标题"),
                "type", Map.of("type", "string", "enum", List.of(
                        "input", "textarea", "number", "slider", "date", "datetime",
                        "switch", "select", "radio", "checkbox", "upload", "editor")),
                "required", Map.of("type", "boolean", "description", "是否必填"),
                "placeholder", Map.of("type", "string", "description", "输入提示"),
                "defaultValue", Map.of("type", "string", "description", "默认值"),
                "options", Map.of("type", "array", "items", option,
                        "description", "select/radio/checkbox 的选项")));
        field.put("required", List.of("field", "title", "type"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "formCode", Map.of("type", "string",
                        "description", "表单编码，小写字母开头，只含字母/数字/下划线"),
                "name", Map.of("type", "string", "description", "表单名称"),
                "fields", Map.of("type", "array", "items", field,
                        "description", "表单字段，按展示顺序排列")));
        schema.put("required", List.of("formCode", "name", "fields"));
        return schema;
    }
}
