package com.example.baseplate.api.model;

import java.util.List;

/** 模块清单声明的规则字段：接入后自动出现在规则配置页下拉里。 */
public record RuleFieldDef(String name, String label, String type, List<String> options) {
}
