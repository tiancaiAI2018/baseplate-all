package com.example.baseplate.registry;

import com.example.baseplate.api.model.ModuleDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 清单校验：id 唯一、必填字段、依赖存在、无循环依赖、版本段不冲突。 */
public final class ModuleDescriptorValidator {

    public void validate(List<LoadedModule> modules) {
        List<String> errors = new ArrayList<>();
        Map<String, LoadedModule> byId = new HashMap<>();

        for (LoadedModule module : modules) {
            ModuleDescriptor d = module.descriptor();
            if (isBlank(d.id())) {
                errors.add("清单缺少 id: " + module.source());
                continue;
            }
            if (isBlank(d.version())) {
                errors.add("模块 " + d.id() + " 缺少 version");
            }
            if (isBlank(d.requires())) {
                errors.add("模块 " + d.id() + " 缺少 requires");
            }
            if (isBlank(d.rootPackage())) {
                errors.add("模块 " + d.id() + " 缺少 root-package");
            }
            LoadedModule previous = byId.putIfAbsent(d.id(), module);
            if (previous != null) {
                errors.add("模块 id 冲突: " + d.id() + " 来自 " + previous.source() + " 与 " + module.source());
            }
        }

        for (LoadedModule module : modules) {
            for (String dependency : module.descriptor().dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    errors.add("模块 " + module.descriptor().id() + " 依赖不存在的模块: " + dependency);
                }
            }
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : byId.keySet()) {
            detectCycle(id, byId, visiting, visited, errors);
        }

        if (!errors.isEmpty()) {
            throw new ModuleLoadException("模块清单校验失败:\n  - " + String.join("\n  - ", errors));
        }
    }

    private void detectCycle(String id, Map<String, LoadedModule> byId,
                             Set<String> visiting, Set<String> visited, List<String> errors) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            errors.add("模块依赖存在环: " + id);
            return;
        }
        LoadedModule module = byId.get(id);
        if (module != null) {
            for (String dependency : module.descriptor().dependsOn()) {
                if (byId.containsKey(dependency)) {
                    detectCycle(dependency, byId, visiting, visited, errors);
                }
            }
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
