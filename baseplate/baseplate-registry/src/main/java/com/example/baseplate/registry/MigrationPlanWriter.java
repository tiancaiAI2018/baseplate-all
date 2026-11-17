package com.example.baseplate.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布期生成 migration-plan.json：按 enabled 过滤模块并汇总 flyway-locations。
 * 应用启动期不执行 migrate，只由部署流水线按此计划执行。
 *
 * <p>手写 JSON：结构固定且很小，不为此引入序列化依赖（Jackson 2/3 混用反而危险）。
 */
public final class MigrationPlanWriter {

    public Path write(List<LoadedModule> modules, Path target) throws IOException {
        List<String> locations = new ArrayList<>();
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"modules\": [\n");

        for (int i = 0; i < modules.size(); i++) {
            LoadedModule module = modules.get(i);
            json.append("    {")
                    .append("\"id\": \"").append(escape(module.descriptor().id())).append("\", ")
                    .append("\"version\": \"").append(escape(module.descriptor().version())).append("\", ")
                    .append("\"enabled\": ").append(module.enabled()).append(", ")
                    .append("\"locations\": [");

            if (module.enabled()) {
                List<String> moduleLocations = module.descriptor().flywayLocations();
                for (int j = 0; j < moduleLocations.size(); j++) {
                    String location = moduleLocations.get(j);
                    locations.add(location);
                    json.append("\"").append(escape(location)).append("\"");
                    if (j < moduleLocations.size() - 1) {
                        json.append(", ");
                    }
                }
            }
            json.append("]}");
            if (i < modules.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  ],\n  \"locations\": [");
        for (int i = 0; i < locations.size(); i++) {
            json.append("\"").append(escape(locations.get(i))).append("\"");
            if (i < locations.size() - 1) {
                json.append(", ");
            }
        }
        json.append("]\n}\n");

        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.writeString(target, json.toString());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
