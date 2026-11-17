package com.example.baseplate.identity.adapter.out.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.baseplate.identity.adapter.out.persistence.entity.UserDO;
import com.example.baseplate.identity.adapter.out.persistence.mapper.UserMapper;

import java.util.Optional;

/** 用户仓储：按 tenant + username 查人，租户条件必须显式带上。 */
public final class UserRepository {

    private final UserMapper mapper;

    public UserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<UserDO> findByUsername(String tenantId, String username) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getTenantId, tenantId)
                .eq(UserDO::getUsername, username)));
    }

    public void updateLoginState(Long userId, boolean mustChangePassword) {
        UserDO row = new UserDO();
        row.setId(userId);
        row.setMustChangePassword(mustChangePassword);
        mapper.updateById(row);
    }
}
