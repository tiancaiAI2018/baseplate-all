package com.example.baseplate.identity.application;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.identity.adapter.out.persistence.IdentityDao;

import java.util.List;

/** 组织架构：部门树，数据权限按 org_id 联动。 */
public final class OrgService {

    private final IdentityDao dao;
    private final CurrentUserProvider currentUser;

    public OrgService(IdentityDao dao, CurrentUserProvider currentUser) {
        this.dao = dao;
        this.currentUser = currentUser;
    }

    public record OrgNode(long id, Long parentId, String name, String path) {
    }

    public List<OrgNode> list() {
        String tenant = currentUser.current().tenantId();
        return dao.listOrgs(tenant).stream()
                .map(org -> new OrgNode(org.id(), org.parentId(), org.name(), org.path()))
                .toList();
    }

    public long create(Long parentId, String name) {
        String tenant = currentUser.current().tenantId();
        String parentPath = "/";
        if (parentId != null) {
            parentPath = dao.findOrg(tenant, parentId)
                    .orElseThrow(() -> new IllegalArgumentException("上级组织不存在")).path();
        }
        long id = dao.insertOrg(tenant, parentId, name, parentPath + "pending");
        String path = parentPath.endsWith("/") ? parentPath + id : parentPath + "/" + id;
        // path 需要 id，插入后回填；用一次额外 update 保证树查询稳定
        dao.updateOrgPath(id, path);
        return id;
    }

    public void rename(long id, String name) {
        dao.updateOrg(id, name);
    }

    public void delete(long id) {
        String tenant = currentUser.current().tenantId();
        dao.findOrg(tenant, id).orElseThrow(() -> new IllegalArgumentException("组织不存在"));
        if (dao.orgSubtreeIds(id).size() > 1) {
            throw new IllegalArgumentException("存在下级组织，不能删除");
        }
        dao.deleteOrg(id);
    }
}
