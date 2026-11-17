package com.example.baseplate.adapter.out.persistence.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** bp_module_registry 的持久化对象：只在适配器内使用，不暴露给 domain。 */
@TableName("bp_module_registry")
public class ModuleRegistryDO {

    @TableId(value = "module_id", type = IdType.INPUT)
    private String moduleId;
    private String version;
    private Boolean enabled;
    private String checksum;
    private OffsetDateTime loadedAt;

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public OffsetDateTime getLoadedAt() {
        return loadedAt;
    }

    public void setLoadedAt(OffsetDateTime loadedAt) {
        this.loadedAt = loadedAt;
    }
}
