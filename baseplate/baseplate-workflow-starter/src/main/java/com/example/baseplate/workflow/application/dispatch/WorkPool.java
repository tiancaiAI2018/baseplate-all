package com.example.baseplate.workflow.application.dispatch;

import java.util.List;

/** 分单池：组织范围是硬边界，池里没配的组织看不到也抢不到。 */
public record WorkPool(
        Long id,
        String code,
        String name,
        String description,
        String status,
        boolean claimEnabled,
        List<WorkPoolOrg> orgs) {

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public List<Long> orgIds() {
        return orgs.stream().map(WorkPoolOrg::orgId).toList();
    }
}
