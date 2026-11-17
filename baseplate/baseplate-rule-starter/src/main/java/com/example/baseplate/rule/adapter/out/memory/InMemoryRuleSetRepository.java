package com.example.baseplate.rule.adapter.out.memory;

import com.example.baseplate.rule.domain.RuleSet;
import com.example.baseplate.rule.domain.RuleSetRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存规则仓储：本地开发与测试用，生产替换为 rule_set / rule_version 表实现。 */
public final class InMemoryRuleSetRepository implements RuleSetRepository {

    private final Map<String, RuleSet> rules = new ConcurrentHashMap<>();

    @Override
    public void save(RuleSet ruleSet) {
        rules.put(ruleSet.scope(), ruleSet);
    }

    @Override
    public Optional<RuleSet> findByScope(String scope) {
        return Optional.ofNullable(rules.get(scope));
    }
}
