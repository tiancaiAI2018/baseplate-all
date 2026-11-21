# BASEPLATE STARTER (AGGREGATOR)

## OVERVIEW
聚合 starter：业务方引入的入口，汇总能力依赖并提供配置模板 `application-template.yml`。

## RULES
- 只做依赖聚合与默认配置模板，不写业务代码。
- 新增能力模块时在这里补依赖，保持业务方「引一个包即可」。
- `application-template.yml` 的 `baseplate.*` 默认值与 `bootstrap/application.yml` 必须一致。

## NOTES
只有一个 `resources/application-template.yml`；`pom.xml` 是实际内容所在。
