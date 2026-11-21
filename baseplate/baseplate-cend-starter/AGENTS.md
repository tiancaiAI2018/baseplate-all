# CEND STARTER (C 端页面)

## OVERVIEW
C 端活动页的公开读取/提交接口与智能体工具。页面 DSL 由前端 tmagic 设计器产出，存于 `metadata` 模块（类型 `CEND_PAGE`），本模块负责公开访问与提交落库。

## STRUCTURE
```
com.example.baseplate.cend/
├── adapter/in/web/CendPublicController.java   # /api/c/pages/{code}、提交
├── application/CendAgentTools.java            # cend_page_list 等智能体工具
├── application/CendAgentToolProvider.java
├── autoconfigure/CendAutoConfiguration.java
resources/db/migration/cend/                   # cend_submission
resources/META-INF/baseplate-module.yml        # cend:view/edit/publish
```

## CONVENTIONS
- 公开接口按 `tenant` 参数取已发布版本；未发布返回 404（`页面不存在或尚未发布`）。
- 提交写 `cend_submission`：必须带 `tenant_id`、`page_code`，`payload` 为 JSONB。
- 智能体工具只读列表；创建/修改页面走外部工具或控制台。
- 权限码：`cend:view` / `cend:edit` / `cend:publish`；删除只能停用。

## ANTI-PATTERNS
- 公开接口返回未发布草稿。
- 提交接口信任前端 `tenant_id`（应来自页面或当前身份校验）。
- 在 `CendPublicController` 里写业务规则；交 Service。
