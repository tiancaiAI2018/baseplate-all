package com.example.baseplate.workflow.application.dispatch;

/** 拒绝请求：reasonCode 必须来自前端枚举，reasonText 在 OTHER 时必填。 */
public record RefuseRequest(String taskId, String type, String reasonCode, String reasonText) {

    public RefuseRequest {
        type = type == null || type.isBlank() ? "ASSIGN_REFUSAL" : type.toUpperCase();
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "OTHER" : reasonCode.toUpperCase();
    }
}
