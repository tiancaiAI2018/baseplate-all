package com.example.baseplate.workflow.application.dispatch;

/** 池的组织范围。includeChildren 必须显式勾选，避免「配了父组织全公司可抢」。 */
public record WorkPoolOrg(Long orgId, boolean includeChildren) {
}
