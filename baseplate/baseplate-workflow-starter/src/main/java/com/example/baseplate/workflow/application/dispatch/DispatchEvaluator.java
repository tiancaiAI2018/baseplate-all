package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.model.DispatchCandidate;
import com.example.baseplate.api.port.out.DispatchCandidateProvider;
import com.example.baseplate.api.port.out.DispatchFilter;
import com.example.baseplate.api.port.out.DispatchScorer;
import com.example.baseplate.api.model.DispatchContext;
import com.example.baseplate.api.model.DispatchFilterResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 过滤 + 打分登记处。业务方通过实现 DispatchFilter / DispatchScorer 扩展，
 * 核心只负责按策略权重聚合，不写死任何行业规则。
 */
public final class DispatchEvaluator {

    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "LEAST_LOAD", 0.35,
            "RATING", 0.20,
            "FAIR", 0.15,
            "SKILL", 0.15,
            "WEIGHT", 0.15);

    private final List<DispatchFilter> filters;
    private final List<DispatchScorer> scorers;

    public DispatchEvaluator(List<DispatchFilter> filters, List<DispatchScorer> scorers) {
        this.filters = filters == null ? List.of() : filters.stream()
                .sorted(Comparator.comparingInt(DispatchFilter::order)).toList();
        this.scorers = scorers == null ? List.of() : scorers.stream()
                .sorted(Comparator.comparingInt(DispatchScorer::order)).toList();
    }

    /** 硬过滤：所有过滤器都通过才留下。淘汰原因逐条记录，用于解释性日志。 */
    public List<Evaluated> filter(DispatchContext context, List<DispatchCandidate> candidates) {
        List<Evaluated> result = new ArrayList<>();
        for (DispatchCandidate candidate : candidates) {
            DispatchFilterResult outcome = DispatchFilterResult.pass();
            for (DispatchFilter filter : filters) {
                DispatchFilterResult one = filter.test(context, candidate);
                if (one != null && !one.passed()) {
                    outcome = one;
                    break;
                }
            }
            result.add(new Evaluated(candidate, outcome.passed(), outcome.reason(), 0));
        }
        return result;
    }

    /** 按策略权重打分；候选人自带的 weight（拒绝惩罚）再乘一次。 */
    public List<Evaluated> score(DispatchContext context, List<Evaluated> evaluated,
                                 Map<String, Object> weights) {
        Map<String, Double> merged = new LinkedHashMap<>(DEFAULT_WEIGHTS);
        if (weights != null) {
            for (Map.Entry<String, Object> entry : weights.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    merged.put(entry.getKey(), number.doubleValue());
                }
            }
        }
        List<Evaluated> result = new ArrayList<>();
        for (Evaluated item : evaluated) {
            if (!item.passed()) {
                result.add(item);
                continue;
            }
            double total = 0;
            for (DispatchScorer scorer : scorers) {
                double weight = merged.getOrDefault(scorer.name(), 0.0);
                if (weight <= 0) {
                    continue;
                }
                total += weight * clamp(scorer.score(context, item.candidate()));
            }
            total *= clamp(item.candidate().weight());
            result.add(new Evaluated(item.candidate(), true, null, round(total)));
        }
        return result;
    }

    /** 分数倒序；同分时优先最近派单少的人，避免总是同一个人吃肉。 */
    public List<Evaluated> ranked(List<Evaluated> evaluated) {
        return evaluated.stream()
                .filter(Evaluated::passed)
                .sorted(Comparator.comparingDouble(Evaluated::score).reversed()
                        .thenComparingInt(item -> item.candidate().recentAssigned())
                        .thenComparing(item -> item.candidate().userId()))
                .toList();
    }

    /** 解释性日志：只保留淘汰原因和分数，不把整个候选对象写进 JSON。 */
    public List<Map<String, Object>> explain(List<Evaluated> evaluated) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Evaluated item : evaluated) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", item.candidate().userId());
            row.put("name", item.candidate().displayName());
            row.put("passed", item.passed());
            row.put("score", item.score());
            row.put("reason", item.reason());
            row.put("active", item.candidate().activeCount());
            row.put("capacity", item.candidate().capacity());
            row.put("checkedIn", item.candidate().checkedIn());
            row.put("rating", item.candidate().rating());
            rows.add(row);
        }
        return rows;
    }

    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        scorers.forEach(scorer -> names.add(scorer.name()));
        return names;
    }

    public record Evaluated(DispatchCandidate candidate, boolean passed, String reason, double score) {
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    static Instant now() {
        return Instant.now();
    }
}
