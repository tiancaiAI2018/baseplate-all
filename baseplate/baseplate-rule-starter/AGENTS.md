# RULE STARTER

## OVERVIEW
结构化规则引擎：条件（`RuleCondition` / `Op`）组合成 `RuleSet`，按字段注册表求值；支持内存/数据库两种仓库。

## STRUCTURE
```
com.example.baseplate.rule/
├── adapter/in/web/RuleController.java
├── adapter/out/persistence/DbRuleSetRepository.java
├── adapter/out/memory/InMemoryRuleSetRepository.java
├── application/StructuredRuleInterpreter.java、RuleFieldRegistry.java
├── domain/RuleSet、RuleCondition、Op、RuleSetRepository.java
└── autoconfigure/RuleAutoConfiguration.java
resources/db/migration/rule/
resources/META-INF/baseplate-module.yml   # rule:view/edit/publish
```

## CONVENTIONS
- 可参与规则判定的字段由各模块清单的 `rule-fields` 声明，`RuleFieldRegistry` 汇总。
- 求值返回 `RuleResult`；规则不直接执行业务动作，由调用方决定。
- 权限码 `rule:view/edit/publish`；菜单挂在 `rule:edit`。

## ANTI-PATTERNS
- 用 `?|` 查 JSONB 数组（应用 `@>`）。
- 在规则里做远程调用。
- 新增操作符不更新 `Op` 与解释器。
