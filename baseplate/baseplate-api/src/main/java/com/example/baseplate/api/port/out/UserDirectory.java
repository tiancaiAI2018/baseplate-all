package com.example.baseplate.api.port.out;

import java.util.Optional;
import java.util.Set;

/**
 * 按用户 ID 取身份快照，供开放平台在「代表用户调用」时重建上下文。
 *
 * <p>刻意复用登录时的同一份权限数据：如果开放链路自己维护一套权限判断，
 * 迟早会和页面上的权限配置不一致，那才是最危险的越权来源。</p>
 */
public interface UserDirectory {

    Optional<Snapshot> load(String userId);

    record Snapshot(String userId, String tenantId, String username,
                    Set<String> roles, Set<String> permissions) {
    }
}
