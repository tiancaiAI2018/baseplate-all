package com.example.baseplate.rule.domain;

import java.util.Optional;

/** 规则仓储：按 scope 取当前生效版本。 */
public interface RuleSetRepository {
    void save(RuleSet ruleSet);

    Optional<RuleSet> findByScope(String scope);
}
