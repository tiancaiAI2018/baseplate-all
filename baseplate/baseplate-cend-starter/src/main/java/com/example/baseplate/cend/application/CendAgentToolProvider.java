package com.example.baseplate.cend.application;

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

public final class CendAgentToolProvider implements AgentToolProvider {

    private static final Set<String> CEND_PAGES = Set.of("cend/pages", "cend/pages/design");

    private final CendAgentTools handler;

    public CendAgentToolProvider(CendAgentTools handler) {
        this.handler = handler;
    }

    @Override
    public List<AgentToolSpec> tools() {
        return List.of(
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_list", "查询C端页面",
                        "列出已保存的 C 端页面。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        CEND_PAGES, "cend:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_get_draft", "读取C端页面",
                        "读取 C 端页面当前 DSL，供再次修改时使用。",
                        ToolType.BUILTIN_INTERNAL, true, false,
                        CEND_PAGES, "cend:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW), handler),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_get_canvas", "读取当前C端页面",
                        """
                        读取用户当前正在编辑的 C 端页面画布（含未保存的修改），
                        返回页面编码、名称和 tmagic DSL。
                        - 用户说「看看我当前这个页面」「基于当前页面改」时先调用；
                        - includeScreenshot=true 时同时返回画布截图，用于确认真实视觉效果；
                        - 设计器未打开时返回原因，如实告诉用户。
                        """,
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        Map.of("type", "object", "properties", Map.of(
                                "includeScreenshot", Map.of("type", "boolean",
                                        "description", "是否返回画布截图") ))), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_get_tree", "读取页面结构",
                        "以树形文本返回当前页面的组件 id、类型、名称和文案。"
                                + "做增量修改前必须先调用它拿到准确的组件 id。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        Map.of("type", "object", "properties", Map.of(
                                "includeScreenshot", Map.of("type", "boolean",
                                        "description", "是否返回画布截图") ))), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_screenshot", "查看真实效果",
                        "截取当前设计器画布，返回图片 data URL。"
                                + "每次调整后调用它确认视觉效果，不要凭空想象页面长什么样。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        Map.of("type", "object", "properties", Map.of())), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_add_component", "添加组件",
                        "向页面或指定容器添加一个组件，返回新组件 id。"
                                + "parentId 不传则加到页面根节点；type 只能是 text/button/img/container/phone-input/share-button。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        editSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_update_component", "修改组件",
                        "按 id 增量修改组件的文案、props 或样式，不重建整页。"
                                + "style 用 CSS 驼峰字段，例如 fontSize/backgroundColor/padding。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        editSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_remove_component", "删除组件",
                        "按 id 删除一个组件；不能删除页面根节点。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        editSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_move_component", "移动组件",
                        "按 id 把组件移动到指定容器 parentId 的 index 位置，用于排序和换父级。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        editSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_bind_event", "绑定事件",
                        "为组件绑定事件动作。action 只能是 goPage/setVariable/callApi/submit/toast/copyLink。"
                                + "绑定后提醒用户在设计器事件面板确认。",
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        editSchema()), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_create_draft", "设计C端页面",
                        """
                        生成 C 端页面并保存草稿，前端会打开 tmagic 可视化设计器供用户查看和修改。
                        - 修改已有页面时 pageCode 传原编码，先调用 cend_page_get_draft 读取现有 DSL；
                        - 每次调用保存为新草稿版本，不会自动发布；发布由用户在设计器确认；
                        - pageCode 必须小写字母开头，只能含小写字母、数字、下划线；
                        - components 按展示顺序排列，type 只能是 text / button / img / container /
                          phone-input / share-button；未知类型会退化为 text；container 用 children 数组嵌套子组件；
                        - text 用 props.text 提供文案；button 用 props.text 和 props.color；
                          img 用 props.src 提供图片地址；phone-input 用 props.placeholder / props.prefix；
                          share-button 用 props.text；container 可设置 style；
                        - style 是内联样式对象，字段用 CSS 驼峰命名，例如 fontSize/backgroundColor/padding；
                        - 不要写 script、expression、eval、class 等危险字段；
                        - background 是页面背景色，建议 6 位十六进制色值；
                        - 生成后可以在设计器里继续拖拽调整、绑定事件。
                        调用成功只代表草稿已打开，不要声称已发布；失败时按原因修正参数重试。
                        """,
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        pageParamsSchema()), null));
    }

    private static Map<String, Object> editSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Map.of("type", "string", "description", "组件 id，先用 cend_page_get_tree 获取"));
        properties.put("parentId", Map.of("type", "string", "description", "目标容器 id"));
        properties.put("index", Map.of("type", "integer", "description", "插入位置"));
        properties.put("type", Map.of("type", "string", "description", "text/button/img/container/phone-input/share-button"));
        properties.put("name", Map.of("type", "string", "description", "组件名称"));
        properties.put("text", Map.of("type", "string", "description", "显示文案"));
        properties.put("props", Map.of("type", "object", "description", "组件属性对象"));
        properties.put("style", Map.of("type", "object", "description", "CSS 驼峰样式对象"));
        properties.put("children", Map.of("type", "array", "description", "子组件数组", "items", Map.of("type", "object")));
        properties.put("event", Map.of("type", "string", "description", "事件名，如 click"));
        properties.put("actions", Map.of("type", "array", "description", "事件动作数组", "items", Map.of("type", "object")));
        properties.put("includeScreenshot", Map.of("type", "boolean", "description", "是否返回画布截图"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        return result;
    }

    private static Map<String, Object> pageParamsSchema() {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "object");
        component.put("properties", Map.of(
                "type", Map.of("type", "string",
                        "enum", List.of("text", "button", "img", "container", "phone-input", "share-button")),
                "name", Map.of("type", "string", "description", "组件名称"),
                "props", Map.of("type", "object", "description", "组件内容，例如 text/src/color"),
                "style", Map.of("type", "object", "description", "内联样式，CSS 驼峰命名"),
                "children", Map.of("type", "array", "description", "容器子组件", "items", Map.of("type", "object"))));
        component.put("required", List.of("type"));

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("type", "object");
        page.put("properties", Map.of(
                "pageCode", Map.of("type", "string",
                        "description", "页面编码，小写字母开头，只含字母/数字/下划线"),
                "name", Map.of("type", "string", "description", "页面名称"),
                "background", Map.of("type", "string", "description", "页面背景色，如 #ffffff"),
                "components", Map.of("type", "array", "items", component,
                        "description", "页面组件列表，按展示顺序排列")));
        page.put("required", List.of("pageCode", "name", "components"));
        return page;
    }
}
