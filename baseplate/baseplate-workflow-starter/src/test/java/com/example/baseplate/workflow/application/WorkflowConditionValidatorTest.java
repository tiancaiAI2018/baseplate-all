package com.example.baseplate.workflow.application;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部署期条件校验回归探针：换表单没改条件必须在部署时报错，
 * 接口自定义变量不能被误伤，系统变量必须放行。
 */
class WorkflowConditionValidatorTest {

    private static final Map<String, Collection<String>> FORMS = new LinkedHashMap<>();

    static {
        FORMS.put("expense_reimbursement", List.of("amount", "reason", "expense_type"));
        FORMS.put("mood_leave_emoji_form", List.of("leave_days", "leave_type", "reason"));
    }

    private static String bpmn(String condition) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="http://baseplate">
                  <process id="P" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="supervisor"/>
                    <exclusiveGateway id="gw"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow_1" sourceRef="start" targetRef="supervisor"/>
                    <sequenceFlow id="flow_2" sourceRef="supervisor" targetRef="gw"/>
                    <sequenceFlow id="flow_3" sourceRef="gw" targetRef="end">
                      <conditionExpression xsi:type="tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">%s</conditionExpression>
                    </sequenceFlow>
                  </process>
                </definitions>
                """.formatted(condition.replace("&", "&amp;")
                        .replace("<", "&lt;").replace(">", "&gt;"));
    }

    private static WorkflowNodeConfig task(String id, String... formCodes) {
        return new WorkflowNodeConfig(id, id, "userTask", "INITIATOR", "", "SINGLE", 100,
                List.of(formCodes), Map.of(), Map.of(), Map.of(), Map.of(), 0);
    }

    @Test
    void identifiersSkipStringsKeywordsAndFunctionNames() {
        assertEquals(java.util.Set.of("leave_days"),
                WorkflowConditionValidator.identifiers("${leave_days <= 1}"));
        assertEquals(java.util.Set.of("amount", "urgent", "dept"),
                WorkflowConditionValidator.identifiers(
                        "${(amount >= 5000 || urgent == true) && dept != 'SALES'}"));
        assertEquals(java.util.Set.of("days"),
                WorkflowConditionValidator.identifiers("${max(days, 1) > 2}"));
        assertEquals(java.util.Set.of("formA__amount"),
                WorkflowConditionValidator.identifiers("${formA__amount > 1}"));
    }

    @Test
    void foreignFormFieldBlocksDeployment() {
        // 真实事故：节点绑了报销表单，网关条件还写请假表单的 leave_days
        WorkflowConditionValidator.Result result = WorkflowConditionValidator.validate(
                bpmn("${leave_days <= 1}"),
                List.of(task("supervisor", "expense_reimbursement")), List.of(), FORMS);

        assertTrue(result.hasErrors(), "必须阻止部署，否则办理到网关才 500");
        assertTrue(result.errorText().contains("leave_days"));
        assertTrue(result.errorText().contains("mood_leave_emoji_form"),
                "报错要指出这个字段属于哪张表单：" + result.errorText());
    }

    @Test
    void boundFormFieldPasses() {
        WorkflowConditionValidator.Result result = WorkflowConditionValidator.validate(
                bpmn("${leave_days <= 1}"),
                List.of(task("supervisor", "mood_leave_emoji_form")), List.of(), FORMS);
        assertFalse(result.hasErrors(), result.errorText());
    }

    @Test
    void baseFormAlsoCountsAsBound() {
        WorkflowConditionValidator.Result result = WorkflowConditionValidator.validate(
                bpmn("${leave_days <= 1}"),
                List.of(task("supervisor")), List.of("mood_leave_emoji_form"), FORMS);
        assertFalse(result.hasErrors(), result.errorText());
    }

    @Test
    void systemVariablesPass() {
        WorkflowConditionValidator.Result result = WorkflowConditionValidator.validate(
                bpmn("${decision == 'REJECT' && (initiator != null)}"),
                List.of(task("supervisor", "expense_reimbursement")), List.of(), FORMS);
        assertFalse(result.hasErrors(), result.errorText());
    }

    @Test
    void unknownVariableOnlyWarns() {
        // 业务接口传的自定义变量不能被当成配置错误挡住部署
        WorkflowConditionValidator.Result result = WorkflowConditionValidator.validate(
                bpmn("${biz_custom_flag == true}"),
                List.of(task("supervisor", "expense_reimbursement")), List.of(), FORMS);
        assertFalse(result.hasErrors(), result.errorText());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("biz_custom_flag"));
    }

    @Test
    void namespacedFieldFromUnboundFormBlocksDeployment() {
        WorkflowConditionValidator.Result result = WorkflowConditionValidator.validate(
                bpmn("${mood_leave_emoji_form__leave_days > 1}"),
                List.of(task("supervisor", "expense_reimbursement")), List.of(), FORMS);
        assertTrue(result.hasErrors());
        assertTrue(result.errorText().contains("mood_leave_emoji_form"));
    }

    @Test
    void missingIdentifierMessageIsHumanReadable() {
        RuntimeException flowable = new RuntimeException("EL error",
                new IllegalStateException("Cannot resolve identifier 'leave_days'"));
        String message = WorkflowConditionValidator.expressionFailureMessage("supervisor", flowable);
        assertNotNull(message);
        assertTrue(message.contains("supervisor"));
        assertTrue(message.contains("leave_days"));
        assertNull(WorkflowConditionValidator.expressionFailureMessage("supervisor",
                new RuntimeException("database down")));
    }
}
