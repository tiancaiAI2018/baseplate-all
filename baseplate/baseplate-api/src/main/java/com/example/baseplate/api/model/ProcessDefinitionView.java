package com.example.baseplate.api.model;

import java.time.Instant;

/**
 * 流程定义视图：只暴露业务需要的字段。
 * 兼容构造器保留给「只关心 key/版本」的旧调用点；新字段由适配器填充。
 */
public record ProcessDefinitionView(
        String id,
        String key,
        String name,
        int version,
        Instant deployedAt,
        String deploymentId,
        boolean suspended,
        boolean hasDiagram,
        int runningInstances,
        int finishedInstances) {

    public ProcessDefinitionView(String id, String key, String name, int version, Instant deployedAt) {
        this(id, key, name, version, deployedAt, null, false, false, 0, 0);
    }
}
