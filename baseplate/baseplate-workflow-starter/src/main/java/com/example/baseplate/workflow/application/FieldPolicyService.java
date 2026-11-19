package com.example.baseplate.workflow.application;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 节点字段权限：把「节点 × 字段」策略真正落到表单数据的读与写上。
 *
 * 背景：历史上 fieldPolicy 只在前端 TaskHandleDrawer 做了展示约束（HIDDEN 不渲染、
 * READONLY 置灰），后端 complete 原样保存前端提交的 formData，等于没有校验——
 * 用户改个请求就能越权改任意字段。本类把校验收回服务端，是纯函数、无 Spring /
 * Flowable 依赖，方便单测覆盖三种权限与越权提交。
 *
 * 语义（方案 B）：
 * - EDITABLE：可写，采纳本次提交值；缺省策略即 EDITABLE，兼容历史空配置。
 * - READONLY：可读不可写，回显下发值（前端置灰），但提交时被忽略，保留库中原值。
 * - HIDDEN：回显不下发值（前端连值都拿不到），提交时同样被忽略。
 *
 * 关键：写入以「库里合并基底」为准，只对 EDITABLE 字段采纳提交值。这样前端隐不隐藏、
 * 置不置灰都只是体验，真正的防线在这里。
 */
public final class FieldPolicyService {

    public static final String EDITABLE = "EDITABLE";
    public static final String READONLY = "READONLY";
    public static final String HIDDEN = "HIDDEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FieldPolicyService() {
    }

    /**
     * 取某字段在该节点的有效策略。空配置/未知字段一律 EDITABLE（向后兼容）；
     * 显式写了非法值的按 READONLY 处理（保守：宁可拒写，不可放写）。
     */
    public static String policyOf(Map<String, Object> fieldPolicy, String field) {
        if (fieldPolicy == null || field == null || !fieldPolicy.containsKey(field)) {
            return EDITABLE;
        }
        Object raw = fieldPolicy.get(field);
        if (raw instanceof String value) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (READONLY.equals(normalized) || HIDDEN.equals(normalized)) {
                return normalized;
            }
            if (EDITABLE.equals(normalized)) {
                return EDITABLE;
            }
        }
        // 显式配置了但值不认识：当作只读，杜绝把非法配置解释成可写
        return READONLY;
    }

    /**
     * 写入裁剪：返回应当落库的完整数据。
     *
     * @param base         库里基底（发起数据 + 历史节点按写入顺序合并后的全表）
     * @param incoming     本次前端提交的 formData
     * @param fieldPolicy  当前节点的字段策略
     * @param schemaFields 表单定义的合法字段名；非空时只接受这些字段，防注入额外 key
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeForWrite(Map<String, Object> base,
                                                     Map<String, Object> incoming,
                                                     Map<String, Object> fieldPolicy,
                                                     Collection<String> schemaFields) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (base != null) {
            result.putAll(base);
        }
        Set<String> allowed = schemaFields == null || schemaFields.isEmpty()
                ? null : new LinkedHashSet<>(schemaFields);
        if (incoming == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String field = entry.getKey();
            if (field == null) {
                continue;
            }
            boolean inSchema = allowed == null || allowed.contains(field);
            if (inSchema && EDITABLE.equals(policyOf(fieldPolicy, field))) {
                result.put(field, entry.getValue());
            }
            // READONLY / HIDDEN / 非 schema 字段：忽略提交，保留 base 原值（没有则保持缺失）
        }
        return result;
    }

    /**
     * 回显裁剪：方案 B 下 HIDDEN 字段连值都不下发，READONLY 字段下发值由前端置灰。
     */
    public static Map<String, Object> viewForRead(Map<String, Object> merged,
                                                  Map<String, Object> fieldPolicy) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (merged == null) {
            return view;
        }
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            if (HIDDEN.equals(policyOf(fieldPolicy, entry.getKey()))) {
                continue;
            }
            view.put(entry.getKey(), entry.getValue());
        }
        return view;
    }

    /** 从 meta_schema.schema_json 解析合法字段名，用于写入时丢弃非表单字段。 */
    @SuppressWarnings("unchecked")
    public static List<String> schemaFieldNames(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> schema = MAPPER.readValue(schemaJson, Map.class);
            Object fields = schema.get("fields");
            if (!(fields instanceof List<?> list)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> field && field.get("name") != null) {
                    names.add(String.valueOf(field.get("name")));
                }
            }
            return names;
        } catch (RuntimeException ex) {
            // schema 损坏不应阻断办理：返回空集，mergeForWrite 退化为「按 fieldPolicy 裁剪」
            return List.of();
        }
    }

    /**
     * 从二维策略里取某个表单的策略子表。空配置返回空表（等价于该表单全字段可编辑）。
     */
    public static Map<String, Object> policyForForm(
            Map<String, Map<String, Object>> fieldPolicyByForm, String formCode) {
        if (fieldPolicyByForm == null || formCode == null) {
            return Map.of();
        }
        Map<String, Object> policy = fieldPolicyByForm.get(formCode);
        return policy == null ? Map.of() : policy;
    }

    /** 二维策略校验：逐个表单校验其内部字段策略。 */
    public static void validatePolicyByForm(Map<String, Map<String, Object>> fieldPolicyByForm) {
        if (fieldPolicyByForm == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : fieldPolicyByForm.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("字段权限的表单编码不能为空");
            }
            validatePolicy(entry.getValue());
        }
    }

    /**
     * 配置校验：字段策略值必须是指定的三种之一，字段名不能为空。
     * 在保存节点配置时调用，把 AI 生成或手工配置的错误挡在运行前。
     */
    public static void validatePolicy(Map<String, Object> fieldPolicy) {
        if (fieldPolicy == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : fieldPolicy.entrySet()) {
            String field = entry.getKey();
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("字段权限的字段名不能为空");
            }
            Object raw = entry.getValue();
            String value = raw instanceof String s ? s.trim().toUpperCase(Locale.ROOT) : "";
            if (!EDITABLE.equals(value) && !READONLY.equals(value) && !HIDDEN.equals(value)) {
                throw new IllegalArgumentException(
                        "字段 " + field + " 的权限值非法：" + raw + "，只能是 EDITABLE/READONLY/HIDDEN");
            }
        }
    }
}
