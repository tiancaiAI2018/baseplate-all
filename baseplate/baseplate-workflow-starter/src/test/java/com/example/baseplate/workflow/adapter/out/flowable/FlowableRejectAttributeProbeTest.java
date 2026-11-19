package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.workflow.application.RejectRouteSupport;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.SequenceFlow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关键探针：Flowable 解析 BPMN 时会丢掉自定义的 flowable:* 属性，但完整保留
 * conditionExpression 文本。因此驳回路由的运行期标记只能是编译后的守卫条件，
 * 不能依赖扩展属性。这个测试把该事实钉死，防止以后有人改回属性方案却没发现问题。
 */
class FlowableRejectAttributeProbeTest {

    @Test
    void customAttributesAreDroppedButConditionTextSurvives() {
        String condition = RejectRouteSupport.guardedExpression("${days > 5}");
        String escaped = condition.replace("&", "&amp;").replace(">", "&gt;");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://baseplate">
                  <process id="P" isExecutable="true">
                    <startEvent id="S"/>
                    <userTask id="A"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="S"
                                  flowable:rejectRoute="true"
                                  flowable:rejectCondition="${days > 5}">
                      <conditionExpression xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:type="tFormalExpression">%s</conditionExpression>
                    </sequenceFlow>
                  </process>
                </definitions>
                """.formatted(escaped);
        BpmnModel model = new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), true, false);
        SequenceFlow flow = (SequenceFlow) model.getFlowElement("F2");

        assertTrue(flow.getAttributes().isEmpty(), "自定义属性被丢弃，不能作为运行期依据");
        assertEquals(condition, flow.getConditionExpression(), "表达式文本被完整保留");
        assertTrue(RejectRouteSupport.isCompiledRejectRoute(flow.getConditionExpression()));
        assertEquals("${days > 5}", RejectRouteSupport.originalCondition(flow.getConditionExpression()));
    }
}
