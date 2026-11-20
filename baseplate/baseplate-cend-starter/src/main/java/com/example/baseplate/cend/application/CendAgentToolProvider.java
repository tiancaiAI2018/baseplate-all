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
                        返回页面编码、名称和区块 DSL。
                        - 用户说「看看我当前这个页面」「基于当前页面改」时先调用；
                        - 修改后调用 cend_page_create_draft 保存草稿并刷新设计器；
                        - 设计器未打开时返回原因，如实告诉用户。
                        """,
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:view", RiskLevel.READ,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        Map.of("type", "object", "properties", Map.of())), null),
                new AgentToolSpec(new ToolDescriptor(
                        "cend_page_create_draft", "设计C端页面",
                        """
                        生成 C 端页面并保存草稿，前端会打开 C 端页面设计器供用户查看和修改。
                        - 修改已有页面时 pageCode 传原编码，先调用 cend_page_get_draft 读取现有 DSL；
                        - 每次调用保存为新草稿版本，不会自动发布；发布由用户在设计器确认；
                        - pageCode 必须小写字母开头，只能含小写字母、数字、下划线；
                        - blocks 按展示顺序排列，type 只能是 hero / heading / text / image / button /
                          card / divider / spacer / form / video / carousel / nav / price / icon /
                          countdown / progress / qrcode / badge / rating / accordion / table / notice；
                          未知类型会退化为 text；
                        - form 区块必须提供 props.formCode 指向已发布的流程表单编码，
                          也可以提供 props.fields 直接生成内嵌字段；
                        - animation.name 只能是 fadeIn / fadeInUp / fadeInDown / slideInLeft /
                          slideInRight / zoomIn / bounceIn / none；不填则无动画；
                        - 不要写 script、expression、eval、class 等危险字段；
                        - 主题配色建议用 6 位十六进制色值。
                        调用成功只代表草稿已打开，不要声称已发布；失败时按原因修正参数重试。
                        """,
                        ToolType.EXTERNAL, true, false,
                        CEND_PAGES, "cend:edit", RiskLevel.WRITE,
                        ApprovalMode.ALLOW, ApprovalMode.ALLOW,
                        pageParamsSchema()), null));
    }

    private static Map<String, Object> pageParamsSchema() {
        Map<String, Object> animation = new LinkedHashMap<>();
        animation.put("type", "object");
        animation.put("properties", Map.of(
                "name", Map.of("type", "string",
                        "enum", List.of("fadeIn", "fadeInUp", "fadeInDown", "slideInLeft",
                                "slideInRight", "zoomIn", "bounceIn", "none")),
                "duration", Map.of("type", "integer", "description", "毫秒"),
                "delay", Map.of("type", "integer", "description", "毫秒")));

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "object");
        block.put("properties", Map.of(
                "type", Map.of("type", "string",
                        "enum", List.of("hero", "heading", "text", "image", "button",
                                "card", "divider", "spacer", "form", "video", "carousel", "nav",
                                "price", "icon", "countdown", "progress", "qrcode", "badge",
                                "rating", "accordion", "table", "notice")),
                "props", Map.of("type", "object", "description", "区块内容，例如 title/text/src/formCode"),
                "style", Map.of("type", "object", "description", "内联样式，驼峰命名"),
                "animation", animation));
        block.put("required", List.of("type"));

        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("type", "object");
        theme.put("properties", Map.of(
                "primary", Map.of("type", "string", "description", "主题色，如 #10b981"),
                "text", Map.of("type", "string", "description", "正文色"),
                "background", Map.of("type", "string", "description", "页面背景"),
                "radius", Map.of("type", "integer", "description", "圆角像素")));

        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("type", "object");
        canvas.put("properties", Map.of(
                "width", Map.of("type", "integer", "description", "画布宽度，默认 375"),
                "mode", Map.of("type", "string", "enum", List.of("mobile", "desktop"))));

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("type", "object");
        page.put("properties", Map.of(
                "pageCode", Map.of("type", "string",
                        "description", "页面编码，小写字母开头，只含字母/数字/下划线"),
                "name", Map.of("type", "string", "description", "页面名称"),
                "canvas", canvas,
                "theme", theme,
                "blocks", Map.of("type", "array", "items", block,
                        "description", "区块列表，按展示顺序排列")));
        page.put("required", List.of("pageCode", "name", "blocks"));
        return page;
    }
}
