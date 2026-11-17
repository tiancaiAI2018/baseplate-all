package com.example.baseplate.api.model;

import java.util.Map;

/** 表单数据：与元数据 Schema 同源校验，前端配置不能绕过后端校验。 */
public record FormData(Map<String, Object> values) {
}
