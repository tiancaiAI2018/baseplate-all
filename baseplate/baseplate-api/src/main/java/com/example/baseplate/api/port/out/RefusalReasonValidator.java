package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.RefusalContext;

/**
 * 拒绝原因校验：防止所有人选「技能不匹配」逃避惩罚。
 * 返回 false 时按 OTHER 处理，照常执行策略。
 */
public interface RefusalReasonValidator {

    boolean valid(RefusalContext context, String reasonCode);

    default int order() {
        return 100;
    }
}
