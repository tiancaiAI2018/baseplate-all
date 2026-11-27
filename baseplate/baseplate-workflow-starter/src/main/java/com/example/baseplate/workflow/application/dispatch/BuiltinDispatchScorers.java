package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.api.model.DispatchContext;
import com.example.baseplate.api.port.out.DispatchScorer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 内置打分器。全部输出 0~1，权重由策略 weights 决定，
 * 新增算法只要实现 DispatchScorer 并注册 Bean。
 */
public final class BuiltinDispatchScorers {

    private BuiltinDispatchScorers() {
    }

    public static DispatchScorer leastLoad() {
        return scored("LEAST_LOAD", (context, candidate) -> candidate.idleRatio());
    }

    public static DispatchScorer rating() {
        return scored("RATING", (context, candidate) ->
                Math.max(0, Math.min(1, (candidate.rating() - 1) / 4.0)));
    }

    public static DispatchScorer fairness() {
        return scored("FAIR", (context, candidate) ->
                Math.max(0, 1 - Math.min(1, candidate.recentAssigned() / 20.0)));
    }

    public static DispatchScorer weight() {
        return scored("WEIGHT", (context, candidate) -> Math.max(0, Math.min(1, candidate.weight())));
    }

    /** 任务变量 requiredSkills 与候选人技能的交集比例；没要求就给满分。 */
    public static DispatchScorer skill() {
        return scored("SKILL", (context, candidate) -> {
            List<String> required = skillsOf(context.variables().get("requiredSkills"));
            if (required.isEmpty()) {
                return 1.0;
            }
            if (candidate.skills().isEmpty()) {
                return 0.0;
            }
            long matched = required.stream()
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .filter(item -> candidate.skills().stream()
                            .anyMatch(skill -> skill.equalsIgnoreCase(item)))
                    .count();
            return (double) matched / required.size();
        });
    }

    private static List<String> skillsOf(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(item -> item != null)
                    .map(item -> String.valueOf(item).trim()).filter(item -> !item.isEmpty()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split("[,，]")).stream().map(String::trim).filter(item -> !item.isEmpty()).toList();
        }
        return List.of();
    }

    private static DispatchScorer scored(String name, ScoreBody body) {
        return new DispatchScorer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public double score(DispatchContext context, DispatchCandidate candidate) {
                return body.score(context, candidate);
            }
        };
    }

    @FunctionalInterface
    private interface ScoreBody {
        double score(DispatchContext context, DispatchCandidate candidate);
    }
}
