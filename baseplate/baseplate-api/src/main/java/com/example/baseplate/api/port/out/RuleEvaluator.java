package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.RuleResult;

import java.util.Map;

/** 规则求值：Dry-run 与正式执行必须共用同一实现。 */
public interface RuleEvaluator {
    RuleResult evaluate(String scope, Map<String, Object> context, boolean dryRun);
}
