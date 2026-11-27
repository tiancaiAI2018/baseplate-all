---
name: 数据洞察
description: 用户问数据、指标、报表、趋势或要图表时使用。用数据源工具发现表结构与业务口径，再执行只读 SQL 并输出卡片。
version: 1
modes: cli
---

# 数据洞察

当前会话提供 `datasource.sales_db.search`、`semantic_model.search`、
`domain_business_knowledge.search`、`sql_guard.check` 四个工具，调用方式见 CLI工具检索 技能。

## 什么时候用

- 「上个月的销售额是多少」「按地区统计订单」
- 「有哪些表」「这张表有哪些字段」「这个字段和哪张表关联」
- 「画个图看看趋势」

## 怎么做（先发现，再确认，最后查询）

1. **确认口径**：用户说的业务词不确定时，先
   `call semantic_model.search {"query": "销售额"}` 和
   `call domain_business_knowledge.search {"query": "销售额"}`。
2. **找表**：`call datasource.sales_db.search {"action": "FIND_TABLES", "query": "订单"}`，
   不确定就 `LIST_TABLES`。只查 `FIND_TABLES` 列出的表。
3. **看结构与关联**：
   ```
   call datasource.sales_db.search {"action": "GET_TABLE_SCHEMA", "tableName": "t_order"}
   call datasource.sales_db.search {"action": "GET_RELATED_TABLES", "tableName": "t_order"}
   ```
   关联结果给出 JOIN 条件，照它写，不要猜字段。
4. **必要时看样例**：`PREVIEW_ROWS` 只在字段含义不明时用，它不是统计结果。
5. **校验再执行**：`call sql_guard.check {"sql": "..."}` 确认 allowed=true 且拿到补好 LIMIT 的 SQL，
   再 `call datasource.sales_db.search {"action": "SEARCH", "sql": "<上一步的 sql>"}`。
6. **回答**：工具会自动给出表格或柱状图卡片；正文只补一句结论和口径假设，
   不复述大段 JSON，不声称未查询到的数据。

## 边界

- 只允许 SELECT / WITH；禁止任何写操作、多语句、危险函数。执行有超时与行数上限。
- 用户要的指标没有对应表或字段时，如实说明，不要编造数字。
- 口径未在知识库里时，说明你的假设（例如「按支付金额、排除退款单」）。
- 图表展示什么由工具结果决定；用户没要图表时，正文简洁回答即可。
