# BASEPLATE APPLICATION

## OVERVIEW
预留扩展位，目前只有 `package-info.java`，没有实际代码。

## RULES
- 需要跨多个能力模块的应用编排时再往这里放；单一能力逻辑留在对应 starter。
- 不要在这里放 Controller / DAO / 框架配置。
- 不要把它变成「什么都往里塞」的杂物层。

## NOTES
`baseplate/pom.xml` 已注册该模块，`dependencyManagement` 也管理其版本；删除前先确认没有外部引用。
