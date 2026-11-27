package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.RefusalContext;

import java.util.Map;

/**
 * 拒绝策略扩展点。type 与池配置 refusal.onXxx[].type 对应。
 * 基础实现（EXCLUDE_TASK / COOLDOWN / BLOCK / WEIGHT / NOTIFY）由 workflow 模块提供，
 * 业务方新增类型只需注册一个 Bean，不改核心代码。
 */
public interface RefusalPolicyHandler {

    String type();

    void apply(RefusalContext context, Map<String, Object> config);

    default int order() {
        return 100;
    }
}
