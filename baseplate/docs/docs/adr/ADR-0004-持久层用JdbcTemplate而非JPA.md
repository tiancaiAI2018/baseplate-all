# ADR-0004 · 持久层用 JdbcTemplate + 显式 SQL（**偏离设计文档**）

- 状态：已接受（2026-09-30），可回退
- 决策人：实现阶段的技术判断，**需用户复核**

## 与设计文档的偏差

文档 1.2 节写的是「Spring Data JPA + jOOQ 风格的复杂查询用 JdbcTemplate」。
本实现**没有引入 JPA**，全部走 `JdbcTemplate` + 显式 SQL。

## 理由

1. **`village_id` 注入必须可静态断言**。文档 3.9 措施②要求：
   「扫描所有 Repository 的 SQL 字符串，不含 village_id 的报错」。
   ORM 的隐式 SQL 断言不到——`@Query` 有字符串，但派生查询（`findByOpenid`）没有。
   现在 `SqlVillageIdScanTest` 能一条不漏地扫到每个 `FROM t_xxx`。
2. **表结构只允许有一个来源**。文档 2.5 明确禁止 `ddl-auto: update`。
   引入 JPA 就意味着存在第二个 schema 来源（Entity ↔ 表），
   两边不一致时要么启动失败要么静默出错。
3. **检索类查询本来就是显式 SQL**。文档 3.6 列了 11 条典型查询模式，
   全是带索引、带排序、带过滤条件的 SQL；用 ORM 方法名表达反而更绕
   （比如「接单中优先 + 最近核实时间倒序」在 SQL 里是一行 ORDER BY）。
4. **无 DB 环境下的可靠性**。本机没有 PG，JPA 的 Entity 与 DDL 是否对得上无法验证。
   用显式 SQL 至少让「SQL 与 DDL」这对关系保持单一。

## 代价

- 每个模块多写一个 `Repository` 与 `RowMapper`，代码量比 JPA Repository 接口大。
- 没有一级缓存、没有脏检查。本项目读多写少、单村 QPS 不到 1，这两项都不重要。
- 换数据库会更痛。但文档 1.3 已把 PG 定为唯一主库（理由是 pgvector），换库不在计划内。

## 回退方式

如果将来要换回 JPA：新增 `biz/*/entity` 与 Spring Data Repository，
`ServiceImpl` 的调用点不变（它只依赖自己模块内的 `*Repository`）。
`SqlVillageIdScanTest` 需要改成扫 `@Query` 注解——**这是回退时最容易漏的一环**。

## 与文档 2.2 依赖规则的配合

JdbcTemplate 只出现在 `biz/*/repo` 与 `infra/*` 两个位置。
AI 包完全不碰它（ArchUnit 规则 1）——这条约束在有 ORM 的情况下会更难守，
因为 ORM 的 `EntityManager` 可以通过各种方式拿到。
