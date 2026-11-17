package com.example.baseplate.api.model;

/** 权限点定义：编码给程序判断，标签与类型给人看。 */
public record PermissionDef(String code, String label, String type) {
}
