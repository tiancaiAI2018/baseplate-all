package com.example.baseplate.rule.domain;

import java.util.List;

/** 规则集：结构化 JSON，版本化发布，不原地修改。 */
public record RuleSet(String id, String version, String scope, MatchMode matchMode,
                      List<RuleCondition> conditions, List<String> actions) {

    public enum MatchMode {
        ANY,
        ALL
    }

    public boolean matches(java.util.Map<String, Object> context) {
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        return matchMode == MatchMode.ALL
                ? conditions.stream().allMatch(condition -> condition.test(context))
                : conditions.stream().anyMatch(condition -> condition.test(context));
    }
}
