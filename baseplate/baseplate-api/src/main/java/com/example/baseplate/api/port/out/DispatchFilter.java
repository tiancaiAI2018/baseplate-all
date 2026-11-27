package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.api.model.DispatchContext;
import com.example.baseplate.api.model.DispatchFilterResult;

/**
 * 硬过滤器：返回不通过即淘汰，不参与打分。
 * 内置过滤器负责签到、冷却、容量、已拒绝；业务方可追加证照、区域等限制。
 */
public interface DispatchFilter {

    DispatchFilterResult test(DispatchContext context, DispatchCandidate candidate);

    default int order() {
        return 100;
    }
}
