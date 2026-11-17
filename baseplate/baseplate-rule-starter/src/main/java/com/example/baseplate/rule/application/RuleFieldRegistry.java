package com.example.baseplate.rule.application;

import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.RuleFieldDef;
import com.example.baseplate.api.port.out.ModuleRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 规则字段注册表：字段来自各模块清单，用户只在下拉里选，不手写字段名。 */
public final class RuleFieldRegistry {

    private final ModuleRegistry moduleRegistry;

    public RuleFieldRegistry(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    public List<RuleFieldDef> fields() {
        Map<String, RuleFieldDef> merged = new LinkedHashMap<>();
        merged.put("amount", new RuleFieldDef("amount", "金额", "NUMBER", List.of()));
        merged.put("userLevel", new RuleFieldDef("userLevel", "用户等级", "STRING",
                List.of("NORMAL", "VIP", "SVIP")));
        for (ModuleDescriptor module : moduleRegistry.loaded()) {
            for (RuleFieldDef field : module.ruleFields()) {
                merged.putIfAbsent(field.name(), field);
            }
        }
        return List.copyOf(merged.values());
    }

    public boolean supports(String name) {
        return fields().stream().anyMatch(field -> field.name().equals(name));
    }
}
