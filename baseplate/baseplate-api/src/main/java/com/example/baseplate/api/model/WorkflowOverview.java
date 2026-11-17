package com.example.baseplate.api.model;

/**
 * 工作流概览：首页/看板只要这几个数，不该为了拿数量去拉全部定义的明细。
 * 明细接口 {@code definitions()} 会带每个定义的运行中/已结束实例数，成本高一个数量级。
 */
public record WorkflowOverview(
        int definitions,
        int runningInstances,
        int finishedInstances,
        int suspendedDefinitions) {
}
