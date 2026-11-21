# METADATA STARTER

## OVERVIEW
低代码元数据：表单元数据（schema/uiSchema）的存取，也是 C 端页面 DSL 的存储后端（`CEND_PAGE`）。

## STRUCTURE
```
com.example.baseplate.metadata/
├── adapter/in/web/MetadataController.java
├── application/MetadataService.java
├── autoconfigure/MetadataAutoConfiguration.java
resources/db/migration/metadata/  # metadata 表、suspend 列
resources/META-INF/baseplate-module.yml
```

## CONVENTIONS
- 元数据按 `tenant_id + code + type` 隔离；查询必须带租户。
- 已发布与草稿分离；公开读取只取已发布版本。
- 模块清单里的 `permissions` / `menus` 当前是注释状态，启用前先确认前端路由与控制台入口。

## ANTI-PATTERNS
- 直接改别的模块的元数据表。
- 把 schema 当可执行代码（必须走白名单/校验）。
