package com.example.baseplate.workflow.adapter.out.flowable;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 给缺 DI（BPMNDiagram 坐标）的存量 BPMN 补一份可渲染的布局。
 *
 * 背景：早期 e2e 脚本部署的 XML 只有流程语义、没有图形坐标，bpmn-js 直接 importXML 会白屏。
 * 这里只在「返回给前端查看」时补齐，不改库里的部署内容，也不影响引擎执行。
 * 布局是简化的按层级横向排布，够看清节点与走向；正式设计器导出的 XML 自带 DI，不会走这里。
 */
public final class BpmnDiagramEnricher {

    private static final String BPMNDI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";
    private static final String DI_NS = "http://www.omg.org/spec/DD/20100524/DI";

    private static final Set<String> NODE_TYPES = Set.of(
            "startEvent", "endEvent", "userTask", "serviceTask", "scriptTask", "task",
            "exclusiveGateway", "parallelGateway", "inclusiveGateway", "eventBasedGateway",
            "callActivity", "subProcess", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent");

    public String ensureDiagram(String xml) {
        if (xml == null || xml.isBlank() || xml.contains("BPMNDiagram")) {
            return xml;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList processes = doc.getElementsByTagNameNS("*", "process");
            if (processes.getLength() == 0) {
                return xml;
            }
            Element process = (Element) processes.item(0);
            String processId = process.getAttribute("id");

            Map<String, Point> positions = new LinkedHashMap<>();
            Map<String, String> nodeTypes = new LinkedHashMap<>();
            List<String[]> flows = new ArrayList<>();
            Map<String, List<String>> outgoing = new LinkedHashMap<>();
            Set<String> hasIncoming = new HashSet<>();
            List<String> starts = new ArrayList<>();

            NodeList children = process.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element child = (Element) children.item(i);
                String type = child.getLocalName();
                String id = child.getAttribute("id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (NODE_TYPES.contains(type)) {
                    positions.put(id, null);
                    nodeTypes.put(id, type);
                    if ("startEvent".equals(type)) {
                        starts.add(id);
                    }
                } else if ("sequenceFlow".equals(type)) {
                    String from = child.getAttribute("sourceRef");
                    String to = child.getAttribute("targetRef");
                    flows.add(new String[]{id, from, to});
                    outgoing.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
                    hasIncoming.add(to);
                }
            }
            if (positions.isEmpty()) {
                return xml;
            }

            // 没有 startEvent 时，把没有入边的节点当起点
            if (starts.isEmpty()) {
                for (String id : positions.keySet()) {
                    if (!hasIncoming.contains(id)) {
                        starts.add(id);
                    }
                }
            }
            if (starts.isEmpty()) {
                starts.add(positions.keySet().iterator().next());
            }

            Map<String, Integer> depth = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>(starts);
            for (String id : starts) {
                depth.put(id, 0);
            }
            while (!queue.isEmpty()) {
                String current = queue.poll();
                int next = depth.get(current) + 1;
                for (String target : outgoing.getOrDefault(current, List.of())) {
                    if (!positions.containsKey(target)) {
                        continue;
                    }
                    Integer old = depth.get(target);
                    if (old == null || old > next) {
                        depth.put(target, next);
                        queue.add(target);
                    }
                }
            }
            Map<Integer, Integer> lane = new HashMap<>();
            int order = 0;
            for (String id : positions.keySet()) {
                int d = depth.getOrDefault(id, order);
                int l = lane.getOrDefault(d, 0);
                lane.put(d, l + 1);
                String type = nodeTypes.getOrDefault(id, "userTask");
                boolean event = type.endsWith("Event");
                boolean gateway = type.endsWith("Gateway");
                int w = event ? 36 : gateway ? 50 : 120;
                int h = event ? 36 : gateway ? 50 : 80;
                // 让所有节点的中心线对齐在同一高度，避免文字压在连线上
                int y = 160 + l * 130 + (event ? 22 : gateway ? 15 : 0);
                positions.put(id, new Point(160 + d * 200, y, w, h));
                order++;
            }

            Element diagram = doc.createElementNS(BPMNDI_NS, "bpmndi:BPMNDiagram");
            diagram.setAttribute("id", "BPMNDiagram_Auto");
            Element plane = doc.createElementNS(BPMNDI_NS, "bpmndi:BPMNPlane");
            plane.setAttribute("id", "BPMNPlane_Auto");
            plane.setAttribute("bpmnElement", processId);
            diagram.appendChild(plane);

            for (Map.Entry<String, Point> entry : positions.entrySet()) {
                Point p = entry.getValue();
                Element shape = doc.createElementNS(BPMNDI_NS, "bpmndi:BPMNShape");
                shape.setAttribute("id", entry.getKey() + "_di");
                shape.setAttribute("bpmnElement", entry.getKey());
                Element bounds = doc.createElementNS(DC_NS, "dc:Bounds");
                bounds.setAttribute("x", String.valueOf(p.x));
                bounds.setAttribute("y", String.valueOf(p.y));
                bounds.setAttribute("width", String.valueOf(p.w));
                bounds.setAttribute("height", String.valueOf(p.h));
                shape.appendChild(bounds);
                plane.appendChild(shape);
            }
            for (String[] flow : flows) {
                Point from = positions.get(flow[1]);
                Point to = positions.get(flow[2]);
                if (from == null || to == null) {
                    continue;
                }
                Element edge = doc.createElementNS(BPMNDI_NS, "bpmndi:BPMNEdge");
                edge.setAttribute("id", flow[0] + "_di");
                edge.setAttribute("bpmnElement", flow[0]);
                if (to.x <= from.x) {
                    // 回环（驳回重做）不能从中间节点横穿过去，绕下方走
                    int below = Math.max(from.y + from.h, to.y + to.h) + 70;
                    edge.appendChild(waypoint(doc, from.x + from.w / 2, from.y + from.h));
                    edge.appendChild(waypoint(doc, from.x + from.w / 2, below));
                    edge.appendChild(waypoint(doc, to.x + to.w / 2, below));
                    edge.appendChild(waypoint(doc, to.x + to.w / 2, to.y + to.h));
                } else {
                    edge.appendChild(waypoint(doc, from.x + from.w, from.y + from.h / 2));
                    edge.appendChild(waypoint(doc, to.x, to.y + to.h / 2));
                }
                plane.appendChild(edge);
            }
            doc.getDocumentElement().appendChild(diagram);

            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception ex) {
            // 补不了就返回原样：前端会提示「该流程缺少图形信息」，但不能让详情接口 500
            return xml;
        }
    }

    private static Element waypoint(Document doc, int x, int y) {
        Element point = doc.createElementNS(DI_NS, "di:waypoint");
        point.setAttribute("x", String.valueOf(x));
        point.setAttribute("y", String.valueOf(y));
        return point;
    }

    private record Point(int x, int y, int w, int h) {
    }
}
