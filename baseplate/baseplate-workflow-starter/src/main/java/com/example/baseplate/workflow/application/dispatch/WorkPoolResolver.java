package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.workflow.adapter.out.persistence.DispatchRepository;

import java.util.List;
import java.util.Optional;

/**
 * 池解析：组织范围是唯一入口。任何候选查询都必须先经过这里展开 orgIds，
 * 业务代码不允许直接查 sys_user 做「全局分单」。
 */
public final class WorkPoolResolver {

    private final DispatchRepository repository;

    public WorkPoolResolver(DispatchRepository repository) {
        this.repository = repository;
    }

    public Optional<WorkPool> byId(String tenant, long poolId) {
        return repository.pool(tenant, poolId);
    }

    public Optional<WorkPool> byCode(String tenant, String code) {
        return repository.poolByCode(tenant, code);
    }

    public List<Long> orgIds(String tenant, WorkPool pool) {
        return repository.resolveOrgIds(tenant, pool.orgs());
    }

    public boolean visible(String tenant, WorkPool pool, String userId) {
        if (pool == null || !pool.active()) {
            return false;
        }
        Long orgId = repository.orgOfUser(tenant, userId);
        return orgId != null && orgIds(tenant, pool).contains(orgId);
    }

    public Long orgOfUser(String tenant, String userId) {
        return repository.orgOfUser(tenant, userId);
    }
}
