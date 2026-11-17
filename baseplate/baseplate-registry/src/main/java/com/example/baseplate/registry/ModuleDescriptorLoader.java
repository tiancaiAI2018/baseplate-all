package com.example.baseplate.registry;

import com.example.baseplate.api.model.MenuDef;
import com.example.baseplate.api.model.ModuleDescriptor;
import com.example.baseplate.api.model.PermissionDef;
import com.example.baseplate.api.model.RuleFieldDef;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * 跨 jar 清单发现：ClassLoader.getResources 扫描 classpath 上所有
 * META-INF/baseplate-module.yml，因此各业务线独立发版的 jar 也能被发现。
 */
public final class ModuleDescriptorLoader {

    private static final String RESOURCE = "META-INF/baseplate-module.yml";

    public List<LoadedModule> load() {
        List<LoadedModule> modules = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            Enumeration<URL> resources = classLoader.getResources(RESOURCE);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (InputStream in = url.openStream()) {
                    modules.add(parse(in, url.toString()));
                }
            }
        } catch (IOException e) {
            throw new ModuleLoadException("扫描模块清单失败", e);
        }
        return modules;
    }

    private LoadedModule parse(InputStream in, String source) {
        Map<String, Object> map = new Yaml().load(in);
        if (map == null) {
            throw new ModuleLoadException("清单为空: " + source);
        }
        ModuleDescriptor descriptor = new ModuleDescriptor(
                str(map.get("id")),
                displayName(map),
                str(map.get("version")),
                str(map.get("requires")),
                strList(map.get("depends-on")),
                str(map.get("root-package")),
                bool(map.get("enabled-by-default"), true),
                permissionCodes(map.get("permissions")),
                strList(map.get("flyway-locations")),
                str(map.get("openapi-group")),
                menus(map.get("menus")),
                ruleFields(map.get("rule-fields")),
                permissionDefs(map.get("permissions")));
        return new LoadedModule(descriptor, source, descriptor.enabledByDefault());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 模块中文名：清单里写 display-name 就用它，没写退回模块 id。
     * 页面上的分组标题、权限目录都读这个值，不再由基盘代码硬编码模块名。
     */
    private static String displayName(Map<String, Object> map) {
        Object value = map.get("display-name");
        String id = str(map.get("id"));
        if (value == null || String.valueOf(value).isBlank()) {
            return id;
        }
        return String.valueOf(value);
    }

    private static List<String> strList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> permissionCodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("code") != null) {
                codes.add(String.valueOf(map.get("code")));
            }
        }
        return List.copyOf(codes);
    }

    private static List<PermissionDef> permissionDefs(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<PermissionDef> defs = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("code") != null) {
                String code = String.valueOf(map.get("code"));
                defs.add(new PermissionDef(code,
                        str(map.get("label")) == null ? code : str(map.get("label")),
                        str(map.get("type")) == null ? "BUTTON" : str(map.get("type"))));
            }
        }
        return List.copyOf(defs);
    }

    private static List<MenuDef> menus(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<MenuDef> menus = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("path") != null) {
                menus.add(new MenuDef(
                        String.valueOf(map.get("path")),
                        str(map.get("label")),
                        str(map.get("permission")),
                        str(map.get("icon"))));
            }
        }
        return List.copyOf(menus);
    }

    private static List<RuleFieldDef> ruleFields(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<RuleFieldDef> fields = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("name") != null) {
                fields.add(new RuleFieldDef(
                        String.valueOf(map.get("name")),
                        str(map.get("label")),
                        str(map.get("type")),
                        strList(map.get("options"))));
            }
        }
        return List.copyOf(fields);
    }

    private static boolean bool(Object value, boolean defaultValue) {
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }
}
