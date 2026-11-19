package com.example.baseplate.workflow.application;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署期连线条件校验：把「条件里引用的变量根本没人提供」挡在部署阶段，而不是等办理时 500。
 *
 * 背景：节点把表单从 A 换成 B，但网关条件还写着 A 的字段（例如 leave_days），
 * 部署时毫不报错，办理到网关才抛 PropertyNotFoundException，前端只看到「服务内部错误」。
 *
 * 严格度（与用户确认）：只硬拦「变量是某个已发布表单的字段、但当前流程没绑那张表」，
 * 这一类几乎必然是表单绑错；完全查不到的变量可能是接口传入的自定义变量，只告警不阻断。
 */
public final class WorkflowConditionValidator {

    /** 引擎/平台自己写入的变量，不来自表单，必须放行。 */
    private static final Set<String> SYSTEM_VARIABLES = Set.of(
            "initiator", "assignee", "decision", "lastOperator", "reason", "comment",
            "tenantId", "formData", "formDataByCode",
            "nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances");

    /** JUEL 关键字与字面量，不是变量。 */
    private static final Set<String> KEYWORDS = Set.of(
            "true", "false", "null", "empty", "and", "or", "not",
            "div", "mod", "eq", "ne", "lt", "gt", "le", "ge", "instanceof");

    // 不把 $ 当标识符：${...} 的 $ 不是变量，Flowable/JUEL 变量名就是 Java 标识符
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern MISSING_IDENTIFIER =
            Pattern.compile("Cannot resolve identifier '([^']+)'");
    private static final int MAX_ITEMS = 6;

    private WorkflowConditionValidator() {
    }

    /**
     * @param bpmnXml        用户上传的原始 XML（不是编译产物）
     * @param configs        节点配置：用于收集当前流程绑定的表单
     * @param baseFormCodes  流程级基础表单
     * @param fieldsByForm   库里所有已发布表单的字段清单：formCode -> field names
     */
    public static Result validate(String bpmnXml, List<WorkflowNodeConfig> configs,
                                  List<String> baseFormCodes,
                                  Map<String, Collection<String>> fieldsByForm) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<FlowCondition> conditions = conditions(bpmnXml);
        if (conditions.isEmpty()) {
            return new Result(errors, warnings);
        }
        Map<String, Collection<String>> forms = fieldsByForm == null ? Map.of() : fieldsByForm;
        Set<String> boundFormCodes = boundFormCodes(configs, baseFormCodes);
        Set<String> availableFields = new LinkedHashSet<>();
        for (String code : boundFormCodes) {
            Collection<String> fields = forms.get(code);
            if (fields != null) {
                availableFields.addAll(fields);
            }
        }
        // 反查：字段名 -> 拥有该字段的表单；用于判断「字段来自别的表单」
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Collection<String>> entry : forms.entrySet()) {
            for (String field : entry.getValue()) {
                owners.computeIfAbsent(field, key -> new ArrayList<>()).add(entry.getKey());
            }
        }

        Set<String> errorKeys = new LinkedHashSet<>();
        Set<String> warningKeys = new LinkedHashSet<>();
        for (FlowCondition condition : conditions) {
            for (String identifier : identifiers(condition.expression())) {
                if (isSystemVariable(identifier) || availableFields.contains(identifier)) {
                    continue;
                }
                String namespacedForm = namespacedForm(identifier);
                if (namespacedForm != null) {
                    if (boundFormCodes.contains(namespacedForm) || !forms.containsKey(namespacedForm)) {
                        continue; // 绑定表里的字段，或不是已知表单的命名空间变量：不阻断
                    }
                    add(errorKeys, errors, "连线 " + condition.flowId() + " 的条件引用了 "
                            + identifier + "，它属于表单 " + namespacedForm
                            + "，但当前流程没有绑定该表单");
                    continue;
                }
                List<String> fieldOwners = owners.get(identifier);
                if (fieldOwners != null && !fieldOwners.isEmpty()) {
                    boolean anyBound = fieldOwners.stream().anyMatch(boundFormCodes::contains);
                    if (!anyBound) {
                        add(errorKeys, errors, "连线 " + condition.flowId() + " 的条件引用了变量 "
                                + identifier + "，它属于表单 " + String.join(" / ", fieldOwners)
                                + "，但当前流程没有绑定该表单");
                    }
                    continue;
                }
                // 不是任何表单字段，也不是系统变量：可能是接口传入的业务变量，只提醒
                add(warningKeys, warnings, "连线 " + condition.flowId() + " 的条件引用了变量 "
                        + identifier + "，它不属于任何已发布表单；若是接口传入的自定义变量可忽略");
            }
        }
        return new Result(errors, warnings);
    }

    /** 把运行期的 PropertyNotFoundException 翻译成可读消息；不是这类错误返回 null。 */
    public static String expressionFailureMessage(String nodeId, Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("Cannot resolve identifier")) {
                Matcher matcher = MISSING_IDENTIFIER.matcher(message);
                String variable = matcher.find() ? matcher.group(1) : "未知变量";
                String where = nodeId == null || nodeId.isBlank() ? "流程启动时" : "节点 " + nodeId;
                return where + "的连线条件引用了不存在的变量 " + variable
                        + "，请检查表单绑定或条件配置";
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    /** 表达式里出现的根变量名；跳过字符串字面量、数字、JUEL 关键字与函数名。 */
    static Set<String> identifiers(String expression) {
        Set<String> result = new LinkedHashSet<>();
        if (expression == null || expression.isBlank()) {
            return result;
        }
        String text = STRING_LITERAL.matcher(expression).replaceAll("''");
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String identifier = matcher.group();
            if (KEYWORDS.contains(identifier.toLowerCase())) {
                continue;
            }
            // 函数调用：标识符紧跟 '('，是方法名而不是变量
            int next = matcher.end();
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next < text.length() && text.charAt(next) == '(') {
                continue;
            }
            result.add(identifier);
        }
        return result;
    }

    private static boolean isSystemVariable(String identifier) {
        return SYSTEM_VARIABLES.contains(identifier)
                || identifier.startsWith("wf_assignee_")
                || identifier.startsWith("wf_assignees_")
                || identifier.startsWith("wf_");
    }

    /** 形如 formCode__field 的命名空间变量，返回表单编码；不是该形式返回 null。 */
    private static String namespacedForm(String identifier) {
        int index = identifier.indexOf("__");
        return index > 0 ? identifier.substring(0, index) : null;
    }

    private static Set<String> boundFormCodes(List<WorkflowNodeConfig> configs, List<String> baseFormCodes) {
        Set<String> codes = new LinkedHashSet<>();
        if (baseFormCodes != null) {
            codes.addAll(baseFormCodes);
        }
        if (configs != null) {
            for (WorkflowNodeConfig config : configs) {
                if (config != null && "userTask".equals(config.nodeType())) {
                    codes.addAll(config.formCodes());
                }
            }
        }
        return codes;
    }

    private static void add(Set<String> keys, List<String> target, String message) {
        if (keys.add(message) && target.size() < MAX_ITEMS) {
            target.add(message);
        }
    }

    private record FlowCondition(String flowId, String expression) {
    }

    private static List<FlowCondition> conditions(String bpmnXml) {
        List<FlowCondition> result = new ArrayList<>();
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return result;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
            NodeList flows = doc.getElementsByTagNameNS("*", "sequenceFlow");
            for (int i = 0; i < flows.getLength(); i++) {
                Element flow = (Element) flows.item(i);
                String expression = null;
                NodeList children = flow.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "conditionExpression".equals(child.getLocalName())) {
                        expression = child.getTextContent();
                        break;
                    }
                }
                if (expression != null && !expression.isBlank()) {
                    result.add(new FlowCondition(flow.getAttribute("id"), expression.trim()));
                }
            }
        } catch (Exception ex) {
            // XML 解析失败交给部署主流程报错，这里不重复拦截
            return List.of();
        }
        return result;
    }

    public record Result(List<String> errors, List<String> warnings) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public String errorText() {
            return String.join("；", errors);
        }
    }
}
