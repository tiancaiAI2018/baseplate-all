package com.example.baseplate.adapter.out.persistence.mybatis;

import com.example.baseplate.adapter.out.persistence.mybatis.entity.ModuleRegistryDO;
import com.example.baseplate.adapter.out.persistence.mybatis.mapper.ModuleRegistryMapper;

import java.time.OffsetDateTime;

/** 模块注册表仓储：启动期把清单 upsert 进真库，便于事后回答「这次跑的是哪些模块」。 */
public final class ModuleRegistryRepository {

    private final ModuleRegistryMapper mapper;

    public ModuleRegistryRepository(ModuleRegistryMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(String moduleId, String version, boolean enabled, String checksum) {
        ModuleRegistryDO existing = mapper.selectById(moduleId);
        ModuleRegistryDO row = new ModuleRegistryDO();
        row.setModuleId(moduleId);
        row.setVersion(version);
        row.setEnabled(enabled);
        row.setChecksum(checksum);
        row.setLoadedAt(OffsetDateTime.now());
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }
}
