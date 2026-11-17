package com.example.baseplate.api.model;

import java.util.List;

/** 规则求值结果：命中审计由实现内部记录。 */
public record RuleResult(boolean matched, String ruleId, String ruleVersion, List<String> actions) {
}
