package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DataScope;

/** 数据范围：显式下传给仓储，不使用 MyBatis-Plus DataPermissionInterceptor。 */
public interface DataScopeProvider {
    DataScope scopeOf(CurrentUser user, String aggregate);
}
