package com.example.baseplate.rule.application;

import com.example.baseplate.api.model.RuleResult;
import com.example.baseplate.api.port.out.RuleEvaluator;
import com.example.baseplate.rule.domain.RuleSet;
import com.example.baseplate.rule.domain.RuleSetRepository;

import java.util.List;
import java.util.Map;

/**
 * 结构化规则解释器：Dry-run 与正式执行共用同一实现。
 * 不做表达式求值，用户配置的只是「字段 + 运算符 + 值」。
 */
public final class StructuredRuleInterpreter implements RuleEvaluator {

    private final RuleSetRepository repository;

    public StructuredRuleInterpreter(RuleSetRepository repository) {
        this.repository = repository;
    }

    @Override
    public RuleResult evaluate(String scope, Map<String, Object> context, boolean dryRun) {
        RuleSet ruleSet = repository.findByScope(scope).orElse(null);
        if (ruleSet == null) {
            return new RuleResult(false, null, null, List.of());
        }
        if (!ruleSet.matches(context)) {
            return new RuleResult(false, ruleSet.id(), ruleSet.version(), List.of());
        }
        return new RuleResult(true, ruleSet.id(), ruleSet.version(),
                ruleSet.actions() == null ? List.of() : ruleSet.actions());
    }
}
