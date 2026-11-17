package com.example.baseplate.api.model;

/** 模块清单声明的菜单项：启动期由清单注册，不手写 SQL 插菜单。 */
public record MenuDef(String path, String label, String permission, String icon) {
}
