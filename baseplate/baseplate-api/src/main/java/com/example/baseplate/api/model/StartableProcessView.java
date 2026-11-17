package com.example.baseplate.api.model;

/** 「发起流程」目录项：最新已发布且我可发起的流程，附带发起表单 schema，普通用户无需 meta:view。 */
public record StartableProcessView(String processKey, String name, String description,
                                   String category, String icon, int version,
                                   String processDefinitionId, String formCode,
                                   String formSchema, String formUiSchema, int sortOrder) {
}