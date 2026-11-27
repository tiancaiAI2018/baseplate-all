package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.api.model.DispatchContext;

/**
 * 打分器：返回值应落在 0~1，最终按策略里的 weights 加权。
 * name() 必须与策略 weights 的 key 一致，例如 LEAST_LOAD / RATING / FAIR / SKILL / WEIGHT。
 */
public interface DispatchScorer {

    String name();

    double score(DispatchContext context, DispatchCandidate candidate);

    default int order() {
        return 100;
    }
}
