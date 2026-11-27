package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.DispatchContext;

import java.util.List;

/**
 * 候选来源扩展点。默认实现从分单池的组织范围取人；
 * 业务方可以追加外部人力、兼职池等来源（必须是池内组织的人，安全边界不外包）。
 */
public interface DispatchCandidateProvider {

    List<String> candidates(DispatchContext context);

    default int order() {
        return 100;
    }
}
