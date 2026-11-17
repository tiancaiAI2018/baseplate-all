package com.example.baseplate.api.model;

import java.util.List;

/** 实例分页结果：total 必须是库里查出来的真总数，不能写 -1。 */
public record WorkflowInstancePage(List<ProcessInstanceView> items, long total) {
}
