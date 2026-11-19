package com.example.baseplate.workflow.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把结构化的流程草稿编译成 BPMN XML。
 *
 * <p>为什么由服务端编译：模型只输出「节点 + 连线」的稳定结构，不直接写 XML，
 * 避免把不可控内容塞进引擎。支持线性流程和排他网关分支；节点配置（找人方式、
 * 表单、签署方式、按钮、超时）同时写进 flowable 属性，并原样保留给前端面板。</p>
 */
public class BpmnDraftComposer {

    /** 节点配置是前端属性面板的同构结构，后端不解释所有字段，只挑关键项写入 BPMN。 */
    public record DraftNode(String id, String name, String type, String assignee,
                            Map<String, Object> config) {
    }

    /** 连线；condition 支持 ${amount >= 5000} 或结构化 field/operator/value。 */
    public record DraftEdge(String id, String from, String to, String name,
                            String condition, Boolean isDefault) {
    }

    public record DraftSpec(String processKey, String name, String description,
                            List<DraftNode> nodes, List<DraftEdge> edges) {
    }

    private static final Set<String> TYPES =
            Set.of("START", "APPROVAL", "EXCLUSIVE_GATEWAY", "END");
    private static final String ID_PATTERN = "[A-Za-z_][A-Za-z0-9_-]{0,63}";
    private static final int MAX_NODES = 40;

    private record Layout(int x, int y, int w, int h) {
    }

    public String compose(DraftSpec spec) {
        List<DraftNode> nodes = spec.nodes() == null ? List.of() : spec.nodes();
        List<DraftEdge> edges = normalizeEdges(nodes, spec.edges());
        validate(spec, nodes, edges);

        Map<String, DraftNode> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        Map<String, List<String>> outgoing = outgoing(nodes, edges);
        Map<String, Integer> depth = depth(nodes, edges);
        Map<String, Layout> layouts = layout(nodes, depth);
        List<String> flowIds = flowIds(edges);
        Map<String, String> gatewayDefaults = gatewayDefaults(byId, edges, flowIds);

        StringBuilder body = new StringBuilder();
        StringBuilder diagram = new StringBuilder();

        for (DraftNode node : nodes) {
            Layout layout = layouts.get(node.id());
            switch (node.type()) {
                case "START" -> body.append("    <startEvent id=\"%s\" name=\"%s\"/>%n"
                        .formatted(node.id(), xml(node.name())));
                case "END" -> body.append("    <endEvent id=\"%s\" name=\"%s\"/>%n"
                        .formatted(node.id(), xml(node.name())));
                case "EXCLUSIVE_GATEWAY" -> {
                    String defaultFlow = gatewayDefaults.get(node.id());
                    body.append("    <exclusiveGateway id=\"%s\" name=\"%s\"%s/>%n"
                            .formatted(node.id(), xml(node.name()),
                                    defaultFlow == null ? "" : " default=\"" + defaultFlow + "\""));
                }
                default -> body.append(userTask(node));
            }
            diagram.append(shape(node, layout));
        }

        for (int i = 0; i < edges.size(); i++) {
            DraftEdge edge = edges.get(i);
            String flowId = flowIds.get(i);
            String condition = conditionExpression(edge);
            body.append("    <sequenceFlow id=\"%s\" sourceRef=\"%s\" targetRef=\"%s\""
                    .formatted(flowId, edge.from(), edge.to()));
            if (edge.name() != null && !edge.name().isBlank()) {
                body.append(" name=\"").append(xml(edge.name())).append("\"");
            }
            if (condition.isBlank()) {
                body.append("/>%n".formatted());
            } else {
                // 注意括号：两个字符串必须先拼再格式化，否则 %n 只会作用于后一段字面量，
                // 结果是 XML 里混进字面量 "%n"，画布/引擎解析都会受影响
                body.append((">%n      <conditionExpression xsi:type=\"tFormalExpression\">"
                        + "<![CDATA[%s]]></conditionExpression>%n    </sequenceFlow>%n")
                        .formatted(condition.replace("]]>", "]]&gt;")));
            }
            diagram.append(edgeDiagram(flowId, edge, layouts));
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://baseplate.local/workflow">
                  <process id="%s" name="%s" isExecutable="true">
                %s  </process>
                  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
                    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="%s">
                %s    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </definitions>
                """.formatted(spec.processKey(), xml(spec.name()), body,
                spec.processKey(), diagram);
    }

    // ==================== 节点与连线输出 ====================

    private String userTask(DraftNode node) {
        Map<String, Object> config = node.config() == null ? Map.of() : node.config();
        StringBuilder attrs = new StringBuilder();
        String assignee = firstNonBlank(node.assignee(), str(config.get("assigneeValue")));
        String assigneeType = str(config.getOrDefault("assigneeType", "")).toUpperCase();
        switch (assigneeType) {
            case "INITIATOR" -> assignee = "${initiator}";
            case "DEPT_MANAGER" -> assignee = assignee.isBlank() ? "${deptManager}" : assignee;
            case "ROLE" -> {
                if (!assignee.isBlank()) {
                    attrs.append(" flowable:candidateGroups=\"").append(xml(assignee)).append("\"");
                    assignee = "";
                }
            }
            default -> {
                if (!assignee.isBlank() && !assignee.contains("${")) {
                    // 用户 ID 直接写；表达式保持原样
                    assignee = assignee;
                }
            }
        }
        if (!assignee.isBlank()) {
            attrs.append(" flowable:assignee=\"").append(xml(assignee)).append("\"");
        }
        String formCode = str(config.get("formCode"));
        if (!formCode.isBlank()) {
            attrs.append(" flowable:formKey=\"").append(xml(formCode)).append("\"");
        }
        Object priority = config.get("priority");
        if (priority != null) {
            attrs.append(" flowable:priority=\"").append(xml(String.valueOf(priority))).append("\"");
        }
        return "    <userTask id=\"%s\" name=\"%s\"%s/>%n"
                .formatted(node.id(), xml(node.name()), attrs);
    }

    /**
     * 条件原样交给流程引擎的表达式求值，支持组合逻辑：
     * ${amount >= 5000 && (dept == 'SALES' || urgent == true)}。
     * 只做长度与包装校验，不尝试解析表达式语法——那是 Flowable 的职责。
     */
    private String conditionExpression(DraftEdge edge) {
        if (edge.condition() == null || edge.condition().isBlank()) {
            return "";
        }
        String condition = edge.condition().trim();
        if (condition.length() > 1000) {
            throw new IllegalArgumentException("连线 " + edge.from() + " -> " + edge.to()
                    + " 的条件过长（最多 1000 字符）");
        }
        if (!condition.startsWith("${")) {
            condition = "${" + condition + "}";
        }
        if (!condition.endsWith("}")) {
            throw new IllegalArgumentException("连线 " + edge.from() + " -> " + edge.to()
                    + " 的条件缺少右花括号：" + condition);
        }
        return condition;
    }

    private String shape(DraftNode node, Layout layout) {
        return """
                      <bpmndi:BPMNShape id="Shape_%s" bpmnElement="%s">
                        <dc:Bounds x="%d" y="%d" width="%d" height="%d"/>
                      </bpmndi:BPMNShape>
                """.formatted(node.id(), node.id(), layout.x(), layout.y(),
                layout.w(), layout.h());
    }

    private String edgeDiagram(String flowId, DraftEdge edge, Map<String, Layout> layouts) {
        Layout source = layouts.get(edge.from());
        Layout target = layouts.get(edge.to());
        int x1 = source.x() + source.w();
        int y1 = source.y() + source.h() / 2;
        int x2 = target.x();
        int y2 = target.y() + target.h() / 2;
        if (x2 <= x1) {
            // 回退边：绕到节点下方，避免与正向线完全重叠
            int mid = x1 + 40;
            int mid2 = x2 - 40;
            int lower = Math.max(source.y() + source.h(), target.y() + target.h()) + 40;
            return """
                              <bpmndi:BPMNEdge id="Edge_%s" bpmnElement="%s">
                                <di:waypoint x="%d" y="%d"/>
                                <di:waypoint x="%d" y="%d"/>
                                <di:waypoint x="%d" y="%d"/>
                                <di:waypoint x="%d" y="%d"/>
                                <di:waypoint x="%d" y="%d"/>
                              </bpmndi:BPMNEdge>
                    """.formatted(flowId, flowId, x1, y1, mid, y1, mid, lower, mid2, lower, x2, y2);
        }
        return """
                          <bpmndi:BPMNEdge id="Edge_%s" bpmnElement="%s">
                            <di:waypoint x="%d" y="%d"/>
                            <di:waypoint x="%d" y="%d"/>
                          </bpmndi:BPMNEdge>
                """.formatted(flowId, flowId, x1, y1, x2, y2);
    }

    // ==================== 连线归一化与布局 ====================

    /**
     * 不传 edges 时退化为节点顺序的直线流程，兼容简单场景；
     * 传了 edges 但漏掉 START 出线时，按节点顺序补一条到第一个审批节点。
     * 该方法幂等，服务层先归一化再落 spec，保证草稿与编译出的 BPMN 一致。
     */
    public List<DraftEdge> normalizeEdges(List<DraftNode> nodes, List<DraftEdge> edges) {
        if (edges != null && !edges.isEmpty()) {
            return ensureStartConnected(nodes, edges);
        }
        List<DraftEdge> result = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            result.add(new DraftEdge(null, nodes.get(i).id(), nodes.get(i + 1).id(),
                    null, null, false));
        }
        return result;
    }

    /** AI 偶尔漏写 START 出线；补到第一个非 START 节点，避免整张图直接不可达。 */
    private List<DraftEdge> ensureStartConnected(List<DraftNode> nodes, List<DraftEdge> edges) {
        DraftNode start = nodes.stream()
                .filter(node -> "START".equals(node.type())).findFirst().orElse(null);
        if (start == null || edges.stream().anyMatch(edge -> start.id().equals(edge.from()))) {
            return edges;
        }
        DraftNode first = nodes.stream()
                .filter(node -> !"START".equals(node.type()))
                .findFirst().orElse(null);
        if (first == null) {
            return edges;
        }
        List<DraftEdge> result = new ArrayList<>(edges);
        result.add(0, new DraftEdge(null, start.id(), first.id(), null, null, false));
        return result;
    }

    private Map<String, List<String>> outgoing(List<DraftNode> nodes, List<DraftEdge> edges) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.id(), new ArrayList<>()));
        edges.forEach(edge -> result.computeIfAbsent(edge.from(), key -> new ArrayList<>())
                .add(edge.to()));
        return result;
    }

    /** BFS 分层；只用于画布坐标，不参与流程语义。 */
    private Map<String, Integer> depth(List<DraftNode> nodes, List<DraftEdge> edges) {
        Map<String, List<String>> outgoing = outgoing(nodes, edges);
        Map<String, Integer> depth = new HashMap<>();
        String startId = nodes.stream().filter(node -> "START".equals(node.type()))
                .map(DraftNode::id).findFirst().orElse(null);
        if (startId == null) {
            return depth;
        }
        depth.put(startId, 0);
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(current) + 1);
                    queue.add(next);
                }
            }
        }
        return depth;
    }

    private Map<String, Layout> layout(List<DraftNode> nodes, Map<String, Integer> depth) {
        Map<Integer, Integer> layerCount = new HashMap<>();
        Map<String, Layout> result = new HashMap<>();
        for (DraftNode node : nodes) {
            int level = depth.getOrDefault(node.id(), 0);
            int index = layerCount.getOrDefault(level, 0);
            layerCount.put(level, index + 1);

            int x = 150 + level * 190;
            int y = 120 + index * 130;
            int width;
            int height;
            switch (node.type()) {
                case "START", "END" -> {
                    width = 36;
                    height = 36;
                    y += 22;
                }
                case "EXCLUSIVE_GATEWAY" -> {
                    width = 50;
                    height = 50;
                    y += 15;
                }
                default -> {
                    width = 120;
                    height = 80;
                }
            }
            result.put(node.id(), new Layout(x, y, width, height));
        }
        return result;
    }

    private List<String> flowIds(List<DraftEdge> edges) {
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < edges.size(); i++) {
            DraftEdge edge = edges.get(i);
            String id = edge.id() == null || edge.id().isBlank()
                    ? "flow_" + (i + 1) : edge.id();
            if (!used.add(id)) {
                throw new IllegalArgumentException("连线 id 重复：" + id);
            }
            result.add(id);
        }
        return result;
    }

    private Map<String, String> gatewayDefaults(Map<String, DraftNode> byId,
                                                List<DraftEdge> edges, List<String> flowIds) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            DraftEdge edge = edges.get(i);
            // BPMN 规范里默认流不参与条件判断，带条件再标 default 是非法组合；
            // 有条件说明分支自己会命中，忽略 default 标记，避免引擎忽略条件
            boolean conditional = edge.condition() != null && !edge.condition().isBlank();
            if (Boolean.TRUE.equals(edge.isDefault()) && !conditional) {
                DraftNode source = byId.get(edge.from());
                if (source != null && "EXCLUSIVE_GATEWAY".equals(source.type())) {
                    result.put(source.id(), flowIds.get(i));
                }
            }
        }
        return result;
    }

    // ==================== 校验 ====================

    private void validate(DraftSpec spec, List<DraftNode> nodes, List<DraftEdge> edges) {
        if (spec.processKey() == null || !spec.processKey().matches(ID_PATTERN)) {
            throw new IllegalArgumentException("流程 key 不合法：" + spec.processKey());
        }
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("流程名称不能为空");
        }
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("节点数量必须在 1-" + MAX_NODES + " 之间");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (DraftNode node : nodes) {
            if (node.id() == null || !node.id().matches(ID_PATTERN)) {
                throw new IllegalArgumentException("节点 id 不合法：" + node.id());
            }
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("节点 id 重复：" + node.id());
            }
            if (!TYPES.contains(node.type())) {
                throw new IllegalArgumentException("不支持的节点类型：" + node.type()
                        + "，可用：START / APPROVAL / EXCLUSIVE_GATEWAY / END");
            }
            if (node.name() == null || node.name().isBlank()) {
                throw new IllegalArgumentException("节点名称不能为空：" + node.id());
            }
        }
        if (nodes.stream().noneMatch(node -> "START".equals(node.type()))) {
            throw new IllegalArgumentException("缺少 START 节点");
        }
        if (nodes.stream().noneMatch(node -> "END".equals(node.type()))) {
            throw new IllegalArgumentException("缺少 END 节点");
        }
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("至少需要一条连线");
        }
        Set<String> pairs = new HashSet<>();
        for (DraftEdge edge : edges) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                throw new IllegalArgumentException("连线引用了不存在的节点："
                        + edge.from() + " -> " + edge.to());
            }
            if (edge.from().equals(edge.to())) {
                throw new IllegalArgumentException("不允许自环：" + edge.from());
            }
            if (!pairs.add(edge.from() + "->" + edge.to())) {
                throw new IllegalArgumentException("重复连线：" + edge.from() + " -> " + edge.to());
            }
        }
        // START 不能有入线，END 不能有出线；网关至少两条出线
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Integer> outgoing = new HashMap<>();
        edges.forEach(edge -> {
            outgoing.merge(edge.from(), 1, Integer::sum);
            incoming.merge(edge.to(), 1, Integer::sum);
        });
        for (DraftNode node : nodes) {
            if ("START".equals(node.type()) && incoming.getOrDefault(node.id(), 0) > 0) {
                throw new IllegalArgumentException("START 节点不能有入线：" + node.id());
            }
            if ("END".equals(node.type()) && outgoing.getOrDefault(node.id(), 0) > 0) {
                throw new IllegalArgumentException("END 节点不能有出线：" + node.id());
            }
            if ("EXCLUSIVE_GATEWAY".equals(node.type())
                    && outgoing.getOrDefault(node.id(), 0) < 2) {
                throw new IllegalArgumentException("排他网关至少需要两条出线：" + node.id());
            }
        }
        // 从 START 必须能到达所有节点
        Map<String, List<String>> adjacency = new HashMap<>();
        edges.forEach(edge -> adjacency.computeIfAbsent(edge.from(), key -> new ArrayList<>())
                .add(edge.to()));
        String startId = nodes.stream().filter(node -> "START".equals(node.type()))
                .map(DraftNode::id).findFirst().orElseThrow();
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(startId);
        reachable.add(startId);
        while (!queue.isEmpty()) {
            for (String next : adjacency.getOrDefault(queue.poll(), List.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        if (!reachable.containsAll(ids)) {
            Set<String> unreachable = new HashSet<>(ids);
            unreachable.removeAll(reachable);
            // 区分「完全没入线」和「有入线但上游断了」，让调用方知道该补哪类连线
            Set<String> noIncoming = new LinkedHashSet<>();
            for (String id : unreachable) {
                if (incoming.getOrDefault(id, 0) == 0) {
                    noIncoming.add(id);
                }
            }
            String hint = noIncoming.isEmpty()
                    ? "这些节点有入线但上游没连到 START，请检查链路是否从 START 一直串下来"
                    : "以下节点没有任何入线：" + noIncoming + "，请补上指向它们的连线";
            throw new IllegalArgumentException("存在从 START 不可达的节点：" + unreachable
                    + "。" + hint);
        }
    }

    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
