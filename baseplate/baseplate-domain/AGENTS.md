# BASEPLATE DOMAIN

## OVERVIEW
共享领域原语，目前只有 `AggregateRoot`。保持极小，不放具体业务。

## RULES
- 不依赖任何框架（Spring / MyBatis / Jackson / slf4j）。
- 只放跨模块复用的领域基元（聚合根、值对象基类、领域事件约定）。
- 具体业务领域对象放各自 starter 的 `domain/` 包。

## ANTI-PATTERNS
- 把某个模块的业务实体搬进来。
- 在这里加注解驱动的 ORM 映射。
