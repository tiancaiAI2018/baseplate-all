package com.example.baseplate.rule.adapter.out.persistence;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.rule.domain.Op;
import com.example.baseplate.rule.domain.RuleCondition;
import com.example.baseplate.rule.domain.RuleSet;
import com.example.baseplate.rule.domain.RuleSetRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规则落库：规则集版本化，发布新版本不覆盖历史。
 * 结构化条件存 JSONB，解释器仍然只接受字段/运算符/值。
 */
public final class DbRuleSetRepository implements RuleSetRepository {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DbRuleSetRepository(JdbcTemplate jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Override
    public void save(RuleSet ruleSet) {
        String tenant = currentUser.current().tenantId();
        try {
            String conditions = objectMapper.writeValueAsString(ruleSet.conditions());
            String actions = objectMapper.writeValueAsString(ruleSet.actions());
            jdbc.update("INSERT INTO rule_set(tenant_id, scope, version, match_mode, conditions_json,"
                            + " actions_json, status) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, 'PUBLISHED')"
                            + " ON CONFLICT (tenant_id, scope, version) DO UPDATE SET"
                            + " match_mode = EXCLUDED.match_mode,"
                            + " conditions_json = EXCLUDED.conditions_json,"
                            + " actions_json = EXCLUDED.actions_json",
                    tenant, ruleSet.scope(), ruleSet.version(), ruleSet.matchMode().name(),
                    conditions, actions);
        } catch (RuntimeException e) {
            throw new IllegalStateException("规则保存失败", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<RuleSet> findByScope(String scope) {
        String tenant = currentUser.current().tenantId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM rule_set WHERE tenant_id = ? AND scope = ? AND status = 'PUBLISHED'"
                        + " ORDER BY id DESC LIMIT 1", tenant, scope);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        try {
            List<Map<String, Object>> rawConditions = objectMapper.readValue(
                    String.valueOf(row.get("conditions_json")), List.class);
            List<RuleCondition> conditions = new ArrayList<>();
            for (Map<String, Object> item : rawConditions) {
                conditions.add(new RuleCondition(String.valueOf(item.get("field")),
                        Op.valueOf(String.valueOf(item.get("op"))), item.get("value")));
            }
            List<String> actions = objectMapper.readValue(String.valueOf(row.get("actions_json")), List.class);
            return Optional.of(new RuleSet(String.valueOf(row.get("id")),
                    String.valueOf(row.get("version")), String.valueOf(row.get("scope")),
                    RuleSet.MatchMode.valueOf(String.valueOf(row.get("match_mode"))),
                    conditions, actions));
        } catch (RuntimeException e) {
            throw new IllegalStateException("规则反序列化失败", e);
        }
    }
}
