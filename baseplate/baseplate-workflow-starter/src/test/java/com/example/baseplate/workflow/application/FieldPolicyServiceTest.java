package com.example.baseplate.workflow.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段权限回归探针：覆盖方案 B 的三个权限态、越权提交、字段注入、回显裁剪与配置校验。
 *
 * 这些用例针对的就是历史上「前端注释声称后端校验、实际后端没校验」的漏洞：
 * 任何 READONLY/HIDDEN 字段即使出现在提交体里，也不允许覆盖库里基底。
 */
class FieldPolicyServiceTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void editableFieldTakesSubmittedValue() {
        Map<String, Object> base = map("amount", 100, "reason", "原值");
        Map<String, Object> submitted = map("amount", 200, "reason", "新值");
        Map<String, Object> policy = map("amount", "EDITABLE", "reason", "EDITABLE");

        Map<String, Object> result = FieldPolicyService.mergeForWrite(
                base, submitted, policy, List.of("amount", "reason"));

        assertEquals(200, result.get("amount"));
        assertEquals("新值", result.get("reason"));
    }

    @Test
    void readonlyFieldIgnoresForgedSubmission() {
        // 攻击者把 READONLY 的 amount 改成 1 提交，必须被忽略，保留基底 100
        Map<String, Object> base = map("amount", 100, "note", "旧备注");
        Map<String, Object> submitted = map("amount", 1, "note", "新备注");
        Map<String, Object> policy = map("amount", "READONLY", "note", "EDITABLE");

        Map<String, Object> result = FieldPolicyService.mergeForWrite(
                base, submitted, policy, List.of("amount", "note"));

        assertEquals(100, result.get("amount"), "READONLY 字段不得被提交值覆盖");
        assertEquals("新备注", result.get("note"));
    }

    @Test
    void hiddenFieldIgnoresSubmissionAndIsNotExposedOnRead() {
        Map<String, Object> base = map("salary", 20000, "name", "张三");
        Map<String, Object> submitted = map("salary", 1, "name", "李四");
        Map<String, Object> policy = map("salary", "HIDDEN", "name", "EDITABLE");

        Map<String, Object> written = FieldPolicyService.mergeForWrite(
                base, submitted, policy, List.of("salary", "name"));
        assertEquals(20000, written.get("salary"), "HIDDEN 字段的值必须保留基底，不被篡改");

        Map<String, Object> view = FieldPolicyService.viewForRead(written, policy);
        assertFalse(view.containsKey("salary"), "HIDDEN 字段连值都不能下发到前端");
        assertEquals("李四", view.get("name"));
    }

    @Test
    void readonlyFieldIsVisibleOnRead() {
        Map<String, Object> merged = map("amount", 500, "secret", "x");
        Map<String, Object> policy = map("amount", "READONLY", "secret", "HIDDEN");

        Map<String, Object> view = FieldPolicyService.viewForRead(merged, policy);

        assertEquals(500, view.get("amount"), "方案 B：READONLY 下发值由前端置灰");
        assertFalse(view.containsKey("secret"));
    }

    @Test
    void unknownSchemaFieldIsDropped() {
        // 前端伪造一个不属于表单的字段 isAdmin=true，必须丢弃
        Map<String, Object> base = map("amount", 100);
        Map<String, Object> submitted = map("amount", 100, "isAdmin", true);
        Map<String, Object> policy = Map.of();

        Map<String, Object> result = FieldPolicyService.mergeForWrite(
                base, submitted, policy, List.of("amount"));

        assertFalse(result.containsKey("isAdmin"), "非 schema 字段不得注入");
    }

    @Test
    void emptyPolicyDefaultsToEditable() {
        Map<String, Object> base = map("amount", 100);
        Map<String, Object> submitted = map("amount", 300);

        Map<String, Object> result = FieldPolicyService.mergeForWrite(
                base, submitted, Map.of(), List.of("amount"));

        assertEquals(300, result.get("amount"), "历史空配置兼容：默认 EDITABLE");
    }

    @Test
    void invalidPolicyValueFailsClosed() {
        // 配置写错成 read_only（下划线）时，policyOf 保守按 READONLY 处理，绝不误开写入
        assertEquals(FieldPolicyService.READONLY, FieldPolicyService.policyOf(map("f", "read_only"), "f"));
        assertEquals(FieldPolicyService.EDITABLE, FieldPolicyService.policyOf(map(), "f"));
    }

    @Test
    void validatePolicyRejectsIllegalValue() {
        assertDoesNotThrow(() -> FieldPolicyService.validatePolicy(
                map("amount", "READONLY", "name", "editable", "x", "HIDDEN")));
        assertThrows(IllegalArgumentException.class,
                () -> FieldPolicyService.validatePolicy(map("amount", "EDITABLEE")));
        assertThrows(IllegalArgumentException.class,
                () -> FieldPolicyService.validatePolicy(map("", "HIDDEN")));
    }

    @Test
    void policyForFormIsolatesSameFieldNameAcrossForms() {
        // 多表单回归探针：两份表单都有 days，权限必须按 formCode 隔离
        Map<String, Map<String, Object>> byForm = Map.of(
                "leave_form", map("days", "READONLY"),
                "expense_form", map("days", "EDITABLE"));

        Map<String, Object> leavePolicy = FieldPolicyService.policyForForm(byForm, "leave_form");
        Map<String, Object> expensePolicy = FieldPolicyService.policyForForm(byForm, "expense_form");

        assertEquals("READONLY", leavePolicy.get("days"));
        assertEquals("EDITABLE", expensePolicy.get("days"));
        assertEquals(Map.of(), FieldPolicyService.policyForForm(byForm, "unknown_form"),
                "没配权限的表单必须退化为全可编辑，而不是串用别的表单策略");
        assertEquals(Map.of(), FieldPolicyService.policyForForm(null, "leave_form"));
    }

    @Test
    void validatePolicyByFormRejectsIllegalConfig() {
        assertDoesNotThrow(() -> FieldPolicyService.validatePolicyByForm(Map.of(
                "leave_form", map("days", "READONLY"),
                "expense_form", map("amount", "editable"))));
        assertThrows(IllegalArgumentException.class,
                () -> FieldPolicyService.validatePolicyByForm(Map.of("", map("days", "HIDDEN"))),
                "表单编码不能为空");
        assertThrows(IllegalArgumentException.class,
                () -> FieldPolicyService.validatePolicyByForm(Map.of("leave_form", map("days", "EDITABLEE"))),
                "二维结构里单个表单的权限值非法也要挡住");
    }

    @Test
    void schemaFieldNamesParsesValidFields() {
        String schema = "{\"fields\":[{\"name\":\"amount\",\"type\":\"MONEY\"},"
                + "{\"name\":\"reason\",\"type\":\"TEXT\"}]}";
        assertEquals(List.of("amount", "reason"), FieldPolicyService.schemaFieldNames(schema));
        assertEquals(List.of(), FieldPolicyService.schemaFieldNames("not-json"));
        assertEquals(List.of(), FieldPolicyService.schemaFieldNames(null));
    }
}
