package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.api.model.WorkflowNodeConfig;
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
        task.removeAttributeNS(FLOWABLE_NS, "formKey");
        removeChildren(task, "multiInstanceLoopCharacteristics");
        if (config.formCode() != null && !config.formCode().isBlank()) {
            task.setAttributeNS(FLOWABLE_NS, "flowable:formKey", config.formCode());
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
        listener.setAttribute("event", "create");
        listener.setAttribute("delegateExpression", RESOLVER);
        extensions.appendChild(listener);
    }

    private void applyCondition(Document doc, Element flow, WorkflowNodeConfig config) {
        Map<String, Object> condition = config.conditionConfig();
        // 自由表达式优先：面板切到「表达式」模式时只填 expression，
        // 支持 && || 和括号，由 Flowable 的表达式引擎求值
        Object rawExpression = condition.get("expression");
        if (rawExpression != null && !String.valueOf(rawExpression).isBlank()) {
            applyExpression(doc, flow, config, String.valueOf(rawExpression).trim());
            return;
        }
        String field = String.valueOf(condition.getOrDefault("field", ""));
        String operator = String.valueOf(condition.getOrDefault("operator", ""));
        String valueType = String.valueOf(condition.getOrDefault("valueType", "STRING"));
        Object rawValue = condition.get("value");
        if (!FIELD.matcher(field).matches() || !OPERATORS.contains(operator) || rawValue == null) {
            throw new IllegalArgumentException("连线 " + config.nodeId() + " 的条件配置不完整或不合法");
        }
        String literal;
        if (NUMERIC_TYPES.contains(valueType.toUpperCase(Locale.ROOT))) {
            try {
                literal = String.valueOf(new java.math.BigDecimal(String.valueOf(rawValue)).stripTrailingZeros());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("连线 " + config.nodeId() + " 的条件值不是数字");
            }
        } else {
            String text = String.valueOf(rawValue);
            if (text.length() > 128 || text.contains("'") || text.contains("$") || text.contains("}")) {
                throw new IllegalArgumentException("连线 " + config.nodeId() + " 的条件值含非法字符");
            }
            literal = "'" + text + "'";
        }
        writeCondition(doc, flow, "${" + field + " " + operator + " " + literal + "}");
    }

    /** 表达式条件：只做包装与长度校验，语法交给流程引擎。 */
    private void applyExpression(Document doc, Element flow, WorkflowNodeConfig config, String raw) {
        String expression = raw.startsWith("${") ? raw : "${" + raw + "}";
        if (!expression.endsWith("}")) {
            throw new IllegalArgumentException("连线 " + config.nodeId() + " 的条件缺少右花括号");
        }
        if (expression.length() > 1000) {
            throw new IllegalArgumentException("连线 " + config.nodeId() + " 的条件过长（最多 1000 字符）");
        }
        writeCondition(doc, flow, expression);
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
