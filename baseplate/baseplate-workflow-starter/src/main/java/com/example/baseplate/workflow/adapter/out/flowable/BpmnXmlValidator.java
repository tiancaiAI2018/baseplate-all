package com.example.baseplate.workflow.adapter.out.flowable;

import java.util.Locale;

/**
 * BPMN XML 安全校验：设计器上传的 XML 必须过这一关，否则「拖拉拽配置流程」
 * 会变成远程代码执行入口。禁止 script 节点、class 委托、脚本任务。
 */
public final class BpmnXmlValidator {

    public void validate(String bpmnXml) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new IllegalArgumentException("BPMN XML 不能为空");
        }
        String lower = bpmnXml.toLowerCase(Locale.ROOT);
        reject(lower, "<script", "禁止 script 节点");
        reject(lower, "flowable:class", "禁止 flowable:class 委托");
        reject(lower, "flowable:executionlistener", "禁止执行监听器");
        reject(lower, "flowable:tasklistener", "禁止任务监听器");
        reject(lower, "scripttask", "禁止脚本任务");
        // BPMN 2.0 通常带命名空间前缀，如 <bpmn:process>，不能只匹配 <process
        if (!java.util.regex.Pattern.compile("<([a-z0-9_]+:)?process\\b").matcher(lower).find()) {
            throw new IllegalArgumentException("缺少 process 定义");
        }
    }

    private static void reject(String xml, String token, String message) {
        if (xml.contains(token)) {
            throw new IllegalArgumentException("BPMN 校验失败：" + message);
        }
    }
}
