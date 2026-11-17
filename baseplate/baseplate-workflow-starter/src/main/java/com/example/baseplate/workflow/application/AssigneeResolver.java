package com.example.baseplate.workflow.application;

import com.example.baseplate.api.model.WorkflowNodeConfig;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 找人逻辑：把「发起人 / 角色 / 部门主管 / 表单字段」这类结构化配置
 * 解析成具体 userId 列表，写成流程变量供 BPMN 引用。
 * 全部在服务端 Java 里执行，用户不写表达式。
 */
public final class AssigneeResolver {

    private final WorkflowConfigRepository repository;

    public AssigneeResolver(WorkflowConfigRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> resolveAll(String tenant, String processKey, int version,
                                          Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        for (WorkflowNodeConfig config : repository.forDefinition(tenant, processKey, version)) {
            if (config.nodeType() != null && !"userTask".equalsIgnoreCase(config.nodeType())) {
                continue;
            }
            List<String> users = usersFor(tenant, config, vars);
            if (users.isEmpty()) {
                users = List.of(String.valueOf(vars.getOrDefault("initiator", "system")));
            }
            result.put(config.collectionVariable(), new ArrayList<>(users));
            result.put(config.assigneeVariable(), users.get(0));
        }
        return result;
    }

    public List<String> usersFor(String tenant, WorkflowNodeConfig config, Map<String, Object> vars) {
        String type = config.assigneeType() == null ? "INITIATOR" : config.assigneeType().toUpperCase(Locale.ROOT);
        String value = config.assigneeValue() == null ? "" : config.assigneeValue();
        Set<String> users = new LinkedHashSet<>();
        switch (type) {
            case "INITIATOR" -> add(users, vars.get("initiator"));
            case "USER" -> {
                for (String part : value.split("[,，]")) {
                    if (!part.isBlank()) {
                        users.add(part.trim());
                    }
                }
            }
            case "ROLE" -> users.addAll(repository.usersByRole(tenant, value));
            case "ORG_ROLE" -> {
                String[] parts = value.split(":", 2);
                if (parts.length == 2) {
                    try {
                        users.addAll(repository.usersByOrgRole(tenant, Long.parseLong(parts[0].trim()),
                                parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                        // 配置不合法时返回空，由调用方回退到发起人
                    }
                }
            }
            case "DEPT_MANAGER" -> {
                Long orgId = repository.orgOfUser(tenant, String.valueOf(vars.getOrDefault("initiator", "")));
                if (orgId != null) {
                    String role = value.isBlank() ? "DEPT_MANAGER" : value;
                    users.addAll(repository.usersByOrgRole(tenant, orgId, role));
                }
            }
            case "PREV_OPERATOR" -> add(users, vars.get("lastOperator"));
            case "FORM_FIELD" -> add(users, vars.get(value));
            default -> add(users, vars.get("initiator"));
        }
        return new ArrayList<>(users);
    }

    private static void add(Set<String> users, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    users.add(String.valueOf(item));
                }
            }
        } else {
            String text = String.valueOf(value);
            if (!text.isBlank() && !"null".equals(text)) {
                users.add(text);
            }
        }
    }
}
