package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 驳回路由编译回归探针：图上画的回退线必须带标记和正向守卫；
 * 普通条件线不能受影响，配置改回去时旧标记必须清掉。
 */
class NodeConfigCompilerRejectRouteTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         targetNamespace="http://baseplate">
              <process id="P" isExecutable="true">
                <startEvent id="StartEvent_1"/>
                <userTask id="Task_A"/>
                <userTask id="Task_B"/>
                <endEvent id="End_1"/>
                <sequenceFlow id="F1" sourceRef="StartEvent_1" targetRef="Task_A"/>
                <sequenceFlow id="F2" sourceRef="Task_A" targetRef="Task_B"/>
                <sequenceFlow id="F3" sourceRef="Task_B" targetRef="Task_A"/>
                <sequenceFlow id="F4" sourceRef="Task_B" targetRef="End_1"/>
              </process>
            </definitions>
            """;

    private static WorkflowNodeConfig flowConfig(String id, Map<String, Object> condition) {
        return new WorkflowNodeConfig(id, id, "sequenceFlow", "INITIATOR", "", "SINGLE", 100,
                List.of(), Map.of(), Map.of(), Map.of(), condition, 0);
    }

    @Test
    void rejectRouteWritesMarkerOriginalConditionAndForwardGuard() {
        String xml = new NodeConfigCompiler().compile(BPMN, List.of(flowConfig("F3",
                Map.of("expression", "${days > 5}", "rejectRoute", true))));

        assertTrue(xml.contains("flowable:rejectRoute=\"true\""), "运行期靠这个标记识别驳回路由");
        assertTrue(xml.contains("flowable:rejectCondition"), "原始条件必须留一份供前端回读和运行期求值");
        assertTrue(xml.contains("decision == 'REJECT'"), "没有守卫，正常审批会沿回退线绕回去");
        assertTrue(xml.contains("days"), "原始条件不能被守卫吃掉");
    }

    @Test
    void unconditionalRejectRouteStillGetsGuard() {
        String xml = new NodeConfigCompiler().compile(BPMN,
                List.of(flowConfig("F3", Map.of("rejectRoute", true))));

        assertTrue(xml.contains("flowable:rejectRoute=\"true\""));
        assertTrue(xml.contains("${decision == 'REJECT' &amp;&amp; true}")
                || xml.contains("${decision == 'REJECT' && true}"));
        assertFalse(xml.contains("rejectCondition"), "无条件路由没有原始条件可存");
    }

    @Test
    void plainConditionLineIsUntouched() {
        String xml = new NodeConfigCompiler().compile(BPMN,
                List.of(flowConfig("F2", Map.of("expression", "${amount > 5}"))));

        assertFalse(xml.contains("rejectRoute"), "普通正向线不能带上驳回标记");
        assertFalse(xml.contains("decision == 'REJECT'"));
        assertTrue(xml.contains("amount"));
    }

    @Test
    void switchingBackToPlainConditionRemovesStaleMarker() {
        NodeConfigCompiler compiler = new NodeConfigCompiler();
        String withRoute = compiler.compile(BPMN,
                List.of(flowConfig("F3", Map.of("expression", "${days > 5}", "rejectRoute", true))));
        assertTrue(withRoute.contains("flowable:rejectRoute=\"true\""));

        String withoutRoute = compiler.compile(withRoute,
                List.of(flowConfig("F3", Map.of("expression", "${days > 3}"))));
        assertFalse(withoutRoute.contains("flowable:rejectRoute"), "旧标记必须清掉");
        assertFalse(withoutRoute.contains("flowable:rejectCondition"));
        assertTrue(withoutRoute.contains("days &gt; 3") || withoutRoute.contains("days > 3"));
    }
}
