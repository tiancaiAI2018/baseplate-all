package com.example.baseplate.rule.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.RuleFieldDef;
import com.example.baseplate.api.model.RuleResult;
import com.example.baseplate.api.port.out.RuleEvaluator;
import com.example.baseplate.rule.application.RuleFieldRegistry;
import com.example.baseplate.rule.domain.Op;
import com.example.baseplate.rule.domain.RuleCondition;
import com.example.baseplate.rule.domain.RuleSet;
import com.example.baseplate.rule.domain.RuleSetRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 规则配置：全下拉可选，后端只接受结构化条件，不接受表达式文本。 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleSetRepository repository;
    private final RuleEvaluator evaluator;
    private final RuleFieldRegistry fieldRegistry;

    public RuleController(RuleSetRepository repository, RuleEvaluator evaluator,
                          RuleFieldRegistry fieldRegistry) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.fieldRegistry = fieldRegistry;
    }

    @GetMapping("/fields")
    @RequiresPermission("rule:view")
    public List<RuleFieldDef> fields() {
        return fieldRegistry.fields();
    }

    @GetMapping("/{scope}")
    @RequiresPermission("rule:view")
    public RuleSet get(@PathVariable String scope) {
        return repository.findByScope(scope)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在：" + scope));
    }

    @PutMapping("/{scope}")
    @RequiresPermission("rule:edit")
    @AuditLog(action = "rule:save", resource = "rule_set")
    public RuleSet save(@PathVariable String scope, @RequestBody SaveRequest request) {
        List<RuleCondition> conditions = new ArrayList<>();
        if (request.conditions() != null) {
            for (ConditionRequest condition : request.conditions()) {
                if (!fieldRegistry.supports(condition.field())) {
                    throw new IllegalArgumentException("未注册的规则字段：" + condition.field());
                }
                conditions.add(new RuleCondition(condition.field(), condition.op(), condition.value()));
            }
        }
        RuleSet ruleSet = new RuleSet(java.util.UUID.randomUUID().toString(),
                request.version() == null ? String.valueOf(System.currentTimeMillis()) : request.version(),
                scope,
                request.matchMode() == null ? RuleSet.MatchMode.ALL : request.matchMode(),
                conditions, request.actions() == null ? List.of() : request.actions());
        repository.save(ruleSet);
        return ruleSet;
    }

    @PostMapping("/{scope}/evaluate")
    @RequiresPermission("rule:view")
    public RuleResult evaluate(@PathVariable String scope, @RequestBody EvaluateRequest request) {
        return evaluator.evaluate(scope, request.context() == null ? Map.of() : request.context(),
                request.dryRun());
    }

    public record ConditionRequest(String field, Op op, Object value) {
    }

    public record SaveRequest(String version, RuleSet.MatchMode matchMode,
                              List<ConditionRequest> conditions, List<String> actions) {
    }

    public record EvaluateRequest(Map<String, Object> context, boolean dryRun) {
    }
}
