package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.workflow.application.RejectRouteSupport;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把结构化节点配置编译成 Flowable 可执行的 BPMN。
 *
 * 用户从来不写表达式：会签比例、网关条件、找人方式都是下拉/勾选，
 * 这里统一生成引擎需要的 assignee、multiInstance、conditionExpression、formKey。
 * 只允许一个固定的受控 delegateExpression，BpmnXmlValidator 会复核。
 */
public final class NodeConfigCompiler {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String RESOLVER = "${wfAssigneeResolver}";
    private static final String DISPATCHER = "${wfDispatchListener}";
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> OPERATORS = Set.of("==", "!=", ">", ">=", "<", "<=");
    private static final Set<String> NUMERIC_TYPES = Set.of("NUMBER", "MONEY");

    public String compile(String bpmnXml, List<WorkflowNodeConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return bpmnXml;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
            NodeList processes = doc.getElementsByTagNameNS("*", "process");
            if (processes.getLength() == 0) {
                throw new IllegalArgumentException("BPMN 缺少 process 定义");
            }
            Element process = (Element) processes.item(0);
            for (WorkflowNodeConfig config : configs) {
                Element node = findById(process, config.nodeId());
                if (node == null) {
                    throw new IllegalArgumentException("节点 " + config.nodeId() + " 不在流程图中，请先在画布删除多余配置");
                }
                String type = node.getLocalName();
                if ("userTask".equals(type)) {
                    applyUserTask(doc, node, config);
                } else if ("sequenceFlow".equals(type) && !config.conditionConfig().isEmpty()) {
                    applyCondition(doc, node, config);
                }
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("节点配置编译失败：" + ex.getMessage(), ex);
        }
    }

    private void applyUserTask(Document doc, Element task, WorkflowNodeConfig config) {
        task.removeAttributeNS(FLOWABLE_NS, "assignee");
        task.removeAttributeNS(FLOWABLE_NS, "candidateUsers");
        // 多表单：BPMN formKey 是单值且全项目无人消费，表单绑定统一由 wf_node_config.form_codes
        // + wf_process_setting.base_form_codes 驱动，这里只清理存量 formKey，不再写入。
        task.removeAttributeNS(FLOWABLE_NS, "formKey");
        removeChildren(task, "multiInstanceLoopCharacteristics");
        // 分单节点不写 assignee 表达式：任务创建后由 DispatchTaskListener 按池策略决定
        // 直接指派还是进抢单池。写死 ${wf_assignee_x} 会先赋值再被覆盖，产生竞争。
        if ("DISPATCH".equalsIgnoreCase(config.assigneeType())) {
            addListener(doc, task, "create", DISPATCHER);
            return;
        }
        String sign = config.signType().toUpperCase(Locale.ROOT);
        boolean sequential = "SEQUENTIAL".equals(sign);
        boolean counter = "COUNTERSIGN".equals(sign) || sequential;
        if (counter) {
            task.setAttributeNS(FLOWABLE_NS, "flowable:assignee", "${wfAssignee}");
            Element multi = doc.createElementNS(BPMN_NS, "bpmn:multiInstanceLoopCharacteristics");
            multi.setAttribute("isSequential", sequential ? "true" : "false");
            multi.setAttributeNS(FLOWABLE_NS, "flowable:collection", "${" + config.collectionVariable() + "}");
            multi.setAttributeNS(FLOWABLE_NS, "flowable:elementVariable", "wfAssignee");
            if (config.signRatio() < 100) {
                Element completion = doc.createElementNS(BPMN_NS, "bpmn:completionCondition");
                completion.setAttributeNS(XSI_NS, "xsi:type", "bpmn:tFormalExpression");
                double ratio = config.signRatio() / 100.0;
                completion.setTextContent("${nrOfCompletedInstances/nrOfInstances >= " + ratio + "}");
                multi.appendChild(completion);
            }
            task.appendChild(multi);
        } else {
            // 单签与或签都交给受控解析器：create 事件里设置 assignee 或 candidateUsers
            task.setAttributeNS(FLOWABLE_NS, "flowable:assignee", "${" + config.assigneeVariable() + "}");
        }
        addResolverListener(doc, task);
    }

    private void addResolverListener(Document doc, Element task) {
        addListener(doc, task, "create", RESOLVER);
    }

    private void addListener(Document doc, Element task, String event, String expression) {
        Element extensions = null;
        NodeList children = task.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && "extensionElements".equals(((Element) child).getLocalName())) {
                extensions = (Element) child;
                break;
            }
        }
        if (extensions == null) {
            extensions = doc.createElementNS(BPMN_NS, "bpmn:extensionElements");
            task.insertBefore(extensions, task.getFirstChild());
        }
        removeChildren(extensions, "taskListener");
        Element listener = doc.createElementNS(FLOWABLE_NS, "flowable:taskListener");
        listener.setAttribute("event", event);
        listener.setAttribute("delegateExpression", expression);
        extensions.appendChild(listener);
    }

    private void applyCondition(Document doc, Element flow, WorkflowNodeConfig config) {
        Map<String, Object> condition = config.conditionConfig();
        boolean rejectRoute = RejectRouteSupport.isRejectRoute(condition);
        // 配置从「驳回路由」改回普通条件线时，旧扩展属性必须一并清掉，不能留脏数据
        flow.removeAttributeNS(FLOWABLE_NS, RejectRouteSupport.REJECT_ROUTE_ATTR);
        flow.removeAttributeNS(FLOWABLE_NS, RejectRouteSupport.REJECT_CONDITION_ATTR);

        String expression = conditionExpression(condition, config.nodeId(), rejectRoute);
        if (rejectRoute) {
            // 显式标记：运行期据此识别驳回路由，不靠「目标是不是前面的节点」猜
            flow.setAttributeNS(FLOWABLE_NS, "flowable:" + RejectRouteSupport.REJECT_ROUTE_ATTR, "true");
            if (expression != null) {
                // 保真原始条件：前端回读设计时用它还原，运行期驳回时用它求值
                flow.setAttributeNS(FLOWABLE_NS,
                        "flowable:" + RejectRouteSupport.REJECT_CONDITION_ATTR, expression);
            }
            // 正向流转守卫：APPROVE 时 decision 恒不等于 REJECT，引擎不会沿回退线绕回去
            writeCondition(doc, flow, RejectRouteSupport.guardedExpression(expression));
        } else if (expression == null) {
            removeChildren(flow, "conditionExpression");
        } else {
            writeCondition(doc, flow, expression);
        }
    }

    /**
     * 从结构化条件或自由表达式算出原始表达式。驳回路由允许不配条件（=无条件命中），
     * 普通条件线仍按原规则要求字段/值完整，保持存量行为不变。
     */
    private String conditionExpression(Map<String, Object> condition, String nodeId, boolean allowEmpty) {
        // 自由表达式优先：面板切到「表达式」模式时只填 expression
        Object rawExpression = condition.get("expression");
        if (rawExpression != null && !String.valueOf(rawExpression).isBlank()) {
            String expression = String.valueOf(rawExpression).trim();
            if (expression.startsWith("${")) {
                if (!expression.endsWith("}")) {
                    throw new IllegalArgumentException("连线 " + nodeId + " 的条件缺少右花括号");
                }
            } else {
                expression = "${" + expression + "}";
            }
            if (expression.length() > 1000) {
                throw new IllegalArgumentException("连线 " + nodeId + " 的条件过长（最多 1000 字符）");
            }
            return expression;
        }
        String field = String.valueOf(condition.getOrDefault("field", ""));
        String operator = String.valueOf(condition.getOrDefault("operator", ""));
        String valueType = String.valueOf(condition.getOrDefault("valueType", "STRING"));
        Object rawValue = condition.get("value");
        if (!FIELD.matcher(field).matches() || !OPERATORS.contains(operator) || rawValue == null) {
            if (allowEmpty) {
                return null;
            }
            throw new IllegalArgumentException("连线 " + nodeId + " 的条件配置不完整或不合法");
        }
        String literal;
        if (NUMERIC_TYPES.contains(valueType.toUpperCase(Locale.ROOT))) {
            try {
                literal = String.valueOf(new java.math.BigDecimal(String.valueOf(rawValue)).stripTrailingZeros());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("连线 " + nodeId + " 的条件值不是数字");
            }
        } else {
            String text = String.valueOf(rawValue);
            if (text.length() > 128 || text.contains("'") || text.contains("$") || text.contains("}")) {
                throw new IllegalArgumentException("连线 " + nodeId + " 的条件值含非法字符");
            }
            literal = "'" + text + "'";
        }
        return "${" + field + " " + operator + " " + literal + "}";
    }

    private void writeCondition(Document doc, Element flow, String expression) {
        removeChildren(flow, "conditionExpression");
        Element element = doc.createElementNS(BPMN_NS, "bpmn:conditionExpression");
        element.setAttributeNS(XSI_NS, "xsi:type", "bpmn:tFormalExpression");
        element.setTextContent(expression);
        flow.appendChild(element);
    }

    private static Element findById(Element process, String id) {
        NodeList children = process.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && id.equals(((Element) child).getAttribute("id"))) {
                return (Element) child;
            }
        }
        return null;
    }

    private static void removeChildren(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(((Element) child).getLocalName())) {
                parent.removeChild(child);
            }
        }
    }
}
