# 08 · P0-c「AI 编排」实施方案 v2（评审稿）

- 版本：**v2**（2026-09-30），取代 v1
- 依据：《村通_后端开发设计文档 v1.0》第 6 章、12.1 P0-c、6.16 评测集
- 决策记录：`adr/ADR-0005-Agent全程接管与流式事件协议.md`
- **状态：第 1 轮 c1 已实现并实测通过（58 个测试全绿，见 06 文档）。c2 待模型 API Key。**

---

## 0. c1 已落地的部分（2026-09-30）

| v2 规划 | 落地情况 |
| --- | --- |
| 10 个工具 | ✅ `com.cuntong.ai.tool.impl`，`AiToolsContractTest` 断言名单恰好 10 个、重名启动即炸 |
| 工具结果结构 | ✅ `ToolResult(summary, cards, refs, data, empty)`，summary 由模板生成，卡片只带 id |
| 卡片装配 | ✅ `CardAssembler`：白名单 / 回库取实时值 / 再校验可见性 / 上限 3 张 |
| AC 规则层 | ✅ `AhoCorasick` + `IntentRuleMatcher`，优先级「正则 > 长关键词 > priority > 出现位置」 |
| 归一化 | ✅ `TextNormalizer`（语气词按长度降序、全角转半角、应急词表） |
| 管道骨架 | ✅ `AskPipeline` + 6 段（Normalize/Rule/Retrieve/Card/PostProcess/Persist），总预算机制 |
| 应急旁路 | ✅ 应急词命中 → `urgent_contacts` 直出，不进常规工具路由 |
| 降级 2/3/4 级 | ✅ 规则未命中→关键词兜底；零命中/工具异常→降级；AI 关闭→仍返回真实结果 |
| 全文检索 | ✅ `biz.kb`（`KnowledgeRetrievalService` + `ChunkRepository`），embedding 列留 NULL |

**一处与本文第 7 节的出入**：`ChunkRetriever` 接口没有放在 ai 包，而是作为
`biz.kb.api.KnowledgeRetrievalService`。原因：AI 包不允许出现数据访问类型（ArchUnit 规则 1/9），
而全文/向量的切换本质是「怎么查表」，放 biz.kb 才能做到 P1 换 pgvector 时 AI 侧一行不改。

**c1 未做（按计划）**：ReActAgent 装配、SSE 事件流、生成段、答案缓存、成本计量与闸门、
记忆三层、知识索引写入（事件消费端）、未命中回流 —— 全部在 c2/c3。

## 1. v2 变更摘要（按你的三条决策）

| 你的决策 | v1 怎么写的 | v2 改成 |
| --- | --- | --- |
| **选 A**：Agent 全程接管 | 推荐 C 双形态（主路径确定性管道） | `ReActAgent` + `Toolkit` 全程接管；「意图集封闭」重新表述为「**工具集封闭（10 个，写死代码）**」；降级链从「跳过某段」改为「Agent 能力降级」 |
| **流式** + 工具调用/状态进度 | 建议 c1/c2 先非流式 | **SSE 事件协议**（第 4 节），11 类事件，工具调用与步骤状态都推给前端；保留非流式接口给 CI |
| **向量 → 全文检索** | 建 chunk，embedding 留 NULL，trgm 检索 | 抽 `ChunkRetriever` 接口，本轮 `FullTextChunkRetriever`，P1 换 `VectorChunkRetriever`；**别名表从「运营优化项」升级为「功能可用性前提」** |

**一处我 v1 判断错了，已在 ADR-0005 认领**：我说选 A 会让「路径不确定 → 评测集写不出断言」。
在流式下这条不成立——`tool_call` 序列本身就是可采集、可断言的执行路径。
**A 的可测性靠流式补回来了**，所以 A 的真实代价只剩成本与延迟，而这两项有量化方案。

---

## 2. 目标与验收（A 形态下重新表述）

**12.1 P0-c 内容**（原文）：

> 规则层 → 意图分类 → 路由与 9 个 Handler → 模板渲染 → 卡片装配 →
> 后处理（脱敏/合规/refs 断言）→ 模型网关 → 降级链 → 答案缓存 · 记忆三层 · 知识索引与事件消费 · 未命中回流

A 形态下这条链路的**职责不变，但执行主体变了**：
「路由与 9 个 Handler」由 Agent 的工具选择完成；其余各段仍然是确定性代码。
所以 9 个 Handler 全部保留，只是**每个 Handler 外面包一层 Tool 描述**。

**验收标准**（沿用 12.1，并补 A 形态新增项）：

| # | 验收项 | 口径 |
| --- | --- | --- |
| 1 | 评测集 120 条：意图准确率 | ≥92%（口径 = 首个 `tool_call` 是否命中期望工具，**不是满意率**） |
| 2 | 结构化答案准确率 | 100%（卡片字段逐字段与库中一致） |
| 3 | 幻觉 | 0 容忍（人工抽检 200 条） |
| 4 | 拔掉模型 API 的降级演练 | 通过（走降级 2 级 → 规则层直连 Tool） |
| 5 | **新增**：事件协议契约 | `seq` 严格单调；`done` 必达；每类事件 ≤4KB |
| 6 | **新增**：首字延迟 | `step` 事件 ≤200ms 到达（不依赖模型） |
| 7 | **新增**：工具选择正确率 | 埋点统计，先只报不拦（无历史基线） |

---

## 3. 架构：Agent 全程接管怎么落地

```
POST /api/ai/ask/stream
        │
        ▼
┌─────────────────────────────────────────────────────────┐
│ AskPipeline  总预算守卫（常规 10s / URGENT 走旁路）      │
│                                                          │
│ ① NormalizeStage   10ms  同音纠错 / 去语气词 / 敏感词     │
│ ② RuleStage         5ms  AC trie 命中 → 预置提示 + 预取   │
│ ③ AgentStage      ← A 形态的核心：ReActAgent + Toolkit     │
│      ├ 每轮：模型决策 → tool_call 事件 → 执行 Tool        │
│      │        → tool_result 事件 → 回灌 → 下一轮          │
│      └ 终止：拿到足够结果 或 步数达 4 或 预算耗尽          │
│ ④ GenerateStage          token 事件逐字推                  │
│ ⑤ PostProcessStage  20ms ComplianceFilter + refs 断言     │
│ ⑥ PersistStage      异步 落库 / 计量 / 未命中回流         │
└─────────────────────────────────────────────────────────┘
        │
        ▼
   SSE 事件流（11 类事件，见第 4 节）
```

### 3.1 工具集封闭 = 意图集封闭的新形态

**10 个工具，写死在代码，不进 `t_config`，不可后台增删。新增要改代码 + 走评审。**

| Tool | 参数 | 返回 | 对应原 Handler |
| --- | --- | --- | --- |
| `search_notice` | keyword?, category?, days? | NoticeVO[] | NEWS_TODAY / NEWS_SEARCH |
| `get_notice_detail` | id | NoticeVO | NEWS_SEARCH |
| `find_contact` | name?, role? | ContactVO[] | CONTACT_LOOKUP |
| `search_provider` | category, groupNo? | ProviderVO[] | SERVICE_SEARCH |
| `get_provider_detail` | id | ProviderVO | SERVICE_SEARCH |
| `search_guide` | keyword | GuideVO[] | GUIDE_RAG |
| `search_wall` | keyword?, postType? | WallVO[] | WALL_SEARCH |
| `get_my_profile` | — | ProfileVO | MY_PROFILE |
| `list_categories` | — | CategoryVO[] | （兜底引导） |
| `urgent_contacts` | — | ContactVO[] | URGENT_HELP |

两条硬约束（ArchUnit 强制）：

- `com.cuntong.ai.tool` 只能依赖 `biz.*.api` 与 `biz.*.dto`，
  **不得出现 `JdbcTemplate` / `DataSource` / `Repository`**（在 v1 规则 1、2 的基础上把包范围扩到 tool）。
- 工具 `description` **只读**，不允许运营改写。它决定模型能不能选对工具，
  改坏等于整个 AI 失效，风险等级等同于系统提示词（文档 6.15 已把提示词定为只读）。

### 3.2 工具返回结构：让「模板优先」活下来

文档 6.11 的「模板优先，模型只润色」在 A 形态下不能丢。做法是把模板下沉到工具里：

```java
record ToolResult(
    String summary,        // ← 模板生成的话术，如「村里登记了 2 个清掏的师傅，都接单中。」
    List<CardRef> cards,   // ← 只带 (cardType, entityId)，字段由 CardAssembler 回库填
    List<Ref> refs,        // ← 来源引用，GUIDE_RAG 必非空
    Object data,           // ← 原始实体，供模型参考（不进卡片）
    boolean empty          // ← 零命中，触发兜底话术
) {}
```

模型拿到 `summary` 后有两种选择：原样使用，或改写得更顺口。
**关键信息位（数量、状态、价格、时间）在 `summary` 里已经是模板产出的**，
模型想改错也得主动改——这比让它从原始数据里自己总结安全得多。

卡片字段**永远**由服务端填（`CardAssembler` 回库取实时值 + 再校验可见性 + 最多 3 张）。
这个契约不变：卡片是用户据以行动的东西（拨号、上门），错一个字就是一次真实损失。

### 3.3 步数与成本上限

| 限制 | 取值 | 理由 |
| --- | --- | --- |
| 单次最多工具轮次 | 3 | 再多的场景属于问题本身不清晰，多走也无用 |
| 单次最多模型调用 | 4（3 轮决策 + 1 次生成） | 成本上限可算 |
| 决策用模型 | LITE 档 | 只需选工具 + 填参 |
| 生成用模型 | STANDARD 档 | 只有这一步需要组织语言 |
| 单次输入 token 上限 | 12000 | 超了直接截断历史并告警 |

---

## 4. 流式事件协议（SSE）

### 4.1 传输

| 项 | 决定 |
| --- | --- |
| 形式 | **SSE over HTTP chunked**，`Content-Type: text/event-stream` |
| 方法 | **POST**（问题文本可能长，且不应进 URL 日志——含个人信息） |
| 小程序 | `wx.request({ enableChunked: true })` + `onChunkReceived`。**需真机 + 弱网验证（未验证项）** |
| 非流式备用 | `POST /api/ai/ask` 保留，给 CI 断言与不支持 chunked 的场景；两者共用同一 `AskPipeline`，只是事件消费者不同 |
| 心跳 | 每 10 秒发注释帧 `:ping`，防 Nginx / 网关空闲超时 |
| 压缩 | 关闭 gzip（会缓冲，破坏即时性） |
| Nginx | `proxy_buffering off; X-Accel-Buffering: no;` |

### 4.2 事件表（这是「能看到在干活」的全部内容）

| event | payload 关键字段 | 前端表现 |
| --- | --- | --- |
| `session` | `sessionId, seq` | 进入回答态，开始计时 |
| `step` | `key, label` | 进度条推进 + 文案（`key`: normalize / rule / plan / retrieve / generate / check） |
| `tool_call` | `tool, label, args` | 「🔧 正在找清掏的师傅…」 |
| `tool_result` | `tool, count, ms, ok` | 打勾；`ok=false` 显示「这个没查到」 |
| `token` | `delta` | 正文逐字追加 |
| `card` | `card`（完整 CardVO） | 卡片插入流 |
| `refs` | `refs[]` | 底部来源条 |
| `notice` | `level, text` | 提示条（降级说明、数据时效提醒） |
| `degraded` | `reason` | 切到降级态样式（M-17） |
| `done` | `meter{inTok,outTok,cachedTok,ms,steps,ruleHit,tools[]}` | 收尾、隐藏进度 |
| `error` | `code, msg` | 错误态 + 重试入口 |

### 4.3 契约（进 CI 断言）

1. `seq` 从 1 严格单调递增，前端按 seq 排序去重。
2. `done` 或 `error` **必达**，且是最后一帧。
3. `tool_call` 与 `tool_result` 成对出现，`tool_result.tool` 必须等于前一个未闭合的 `tool_call.tool`。
4. 单帧 ≤4KB；卡片单独一帧（不做增量拼接）。
5. 首帧 `step` ≤200ms。**这一条不依赖模型，是纯管道能力**，
   也是「用户不觉得卡住」的真正来源——不是模型快，是事件密。

### 4.4 一次提问的时序（常规路径）

```
t=   0ms  session
t=  10ms  step(normalize)   ← 首字达标
t=  30ms  step(rule)        ← AC 命中「谁抽粪」→ 预置 category=清掏
t=  50ms  step(plan)
t= 280ms  tool_call(search_provider, {category:"清掏"})
t= 400ms  tool_result(search_provider, count:2, ms:120)
t= 600ms  card(张师傅)  card(李师傅)         ← 用户此时已经看到结果了
t= 650ms  step(generate)
t= 900ms  token("村里登记了 ")  token("2 个")  …（逐字）
t=2400ms  refs
t=2450ms  done(meter{...})
```

**注意 t=600ms 卡片就到位了**。即便模型生成卡到 5 秒，用户也已经拿到可用信息——
这就是降级链第 1 级在流式下「免费获得」的原因。

---

## 5. 降级链（A + 流式下的重新表述）

| 级 | 触发 | 返回 | 屏号 |
| --- | --- | --- | --- |
| 0 | 正常 | 工具结果 + 卡片 + 引用 + 模型正文 | M-11~M-15 |
| 1 | 生成慢（卡片已推完） | **卡片 + 模板话术先到，正文后补或就不补** | M-11~M-15（文案略生硬，信息完整） |
| 2 | Agent 首轮决策失败/超时 | 跳过 Agent，**用规则层命中的意图直接跑对应 Tool** + 模板拼装 | M-16 / M-06 形态 |
| 3 | 闸门 / 熔断 / 模型不可用 | `degraded=true` + 工具结果直出 | M-17 |
| 4 | 工具全失败 | 类目宫格 + 通讯录入口 | M-20 |
| 5 | 全站不可用 | 本地应急号码页 | M-42 |

与文档 6.14 的差别只在第 1、2 级的**实现方式**（文档按「跳段」写，这里按「Agent 能力降级」写），
**返回内容与屏号完全一致**，所以用户看到的行为不变。

---

## 6. URGENT_HELP 旁路（必须，否则应急预案失效）

应急场景不能让模型规划——10 秒预算对「着火/溺水」是不可接受的。

**触发**：正则命中应急词表（着火 / 溺水 / 急救 / 触电 / 煤气 / 有人晕倒…）
或 `urgent=true` 参数。

**路径**：`Normalize → Rule → urgent_contacts Tool → 立刻推卡片 + 模板话术`。
**不进 Agent、不调模型**，总预算 800ms（文档 6.1 对 URGENT_HELP 的要求）。

这条旁路是 A 形态**必须**的补丁：A 让常规路径变强了，但会让应急路径变慢。
不能因为选了 A 就把文档 6.1 的 URGENT 预算丢掉。

---

## 7. 向量 → 全文检索

```java
public interface ChunkRetriever {
    List<ChunkHit> search(RetrieveQuery query);   // AI 侧唯一检索入口
}
```

| 实现 | 选型 | 状态 |
| --- | --- | --- |
| `FullTextChunkRetriever` | `content_text % :q`（pg_trgm GIN）+ `similarity()` 排序；查询串过 `ChineseTokenizer.bigram()` | **本轮** |
| `VectorChunkRetriever` | `embedding <=> :vec`（HNSW，`vector_cosine_ops`） | P1 |

切换靠 `cuntong.ai.retriever=fulltext|vector`。
`t_kb_chunk.embedding` 列**本轮建但留 NULL**，P1 只需回填 + 换实现，不动表结构。

### 必须写清的能力边界

> **全文检索只解决「字面重合」，语义相似解决不了。**

「我来拉粪」→ 找 `清掏` 的师傅，靠的是 `t_service_category.aliases` 里配了
`["拉粪","掏粪","抽粪","化粪池","沼液车"]`，**不是**靠 trgm。
trgm 是字符三元组匹配，对中文长句的召回本来就不强（这一点文档 6.16 已列为未实测项）。

所以结论变了：

| 项 | v1 定位 | v2 定位 |
| --- | --- | --- |
| 别名表维护 | 运营优化项（「能提升八成准确率」） | **功能可用性前提** —— 没配别名就等于搜不到 |

这直接影响上线检查清单：**别名表必须逐个类目人工过一遍**，
不能再当成「上线后再补」。同时 P1 引入向量之后，别名表仍然要留——
它解决的是**确定性归一**（别名 → 类目 id），向量解决的是**模糊相似**，两者是互补不是替代。

---

## 8. 总预算与超时调整（**需确认**）

| 场景 | 超时 | 失败行为 |
| --- | --- | --- |
| NormalizeStage | 10 ms | SKIP |
| RuleStage | 5 ms | SKIP |
| **AgentStage 单轮** | 2500 ms | 该轮失败 → 降级 2 级 |
| **AgentStage 合计** | 6000 ms | 终止循环，用已有结果进 Generate |
| GenerateStage | 2000 ms | SKIP → 回退模板话术（降级 1 级） |
| PostProcessStage | 20 ms | FATAL（拒答） |
| PersistStage | 异步 | SKIP |
| **总预算（常规）** | **10 s** | 超时按当前已有结果收尾 |
| **总预算（URGENT）** | **800 ms** | 走旁路，不进 Agent |

文档 6.1 的 3 秒在 A 形态下不成立，10 秒是我按「3 轮工具 + 1 次生成」估的，
**不是实测值**。这条要你确认，因为它直接决定用户等待体验与验收口径
（文档写的「AI 提问 P95 < 3s」也要一并改成 `< 10s`，否则验收时对不上）。

---

## 9. 成本重估（**必须实测校准**）

| 项 | v1 估算 | A 形态估算 | 倍数 |
| --- | --- | --- | --- |
| 单次输入 token | ~130（规则命中，零调用） | 6000–9000 | — |
| 单次输出 token | ~200 | ~400 | 2× |
| 单次模型调用数 | 0–2 | 2–4 | 2× |
| token 总量 | 1× | 3–5× | 3–5× |
| 单村月成本 | ≈3.9 元（09-29 实测口径） | **≈12–20 元** | — |

**这张表是估算，不是实测。** 文档 1.3 的结论「模型费不是商业模式约束」
是按确定性管道算的；A 形态下**已接近 20 元闸门**，这个结论需要重新验证。

四项管控（成本闸门之外的）：
1. 步数上限 4 次调用（硬上限，可算上界）
2. 决策用 LITE 档，只有生成用 STANDARD
3. **工具结果做缓存，答案不做缓存** —— 检索结果才是数据源，
   每次都让模型重新组织语言既更安全（数据新鲜）又省掉大部分重复成本
4. 工具 `description` 精简（schema 每次都重发，描述越长越贵）

---

## 10. 评测集与断言（A + 流式下反而更好断言）

120 条仍按文档 6.16 分层抽样。**断言项从事件流里取**：

| # | 断言 | 数据来源 |
| --- | --- | --- |
| ① | 首个 `tool_call` == 期望工具 | 事件流 |
| ② | 卡片字段与库中数据逐字段一致 | `card` 事件 vs 直接查库 |
| ③ | `refs` 非空（`search_guide` 场景） | `refs` 事件 |
| ④ | `degraded == false` | `degraded` / `done` 事件 |
| ⑤ | `seq` 严格单调、`done` 必达 | 事件流 |
| ⑥ | 无 11 位手机号出现在任何 `token` 帧 | 事件流正则 |

跑法：CI 用 test profile + **录制的模型响应**（可复现、不烧钱）；
另有一套「真机冒烟」每天 10 条真实调用，只报不拦。
门禁：意图准确率 <92% → CI 失败，不允许合并。

> **口径提醒**（文档 6.16 原话）：这里的「准确率」是**意图判定正确率**，不是用户满意率。
> A 形态下还要补一句：它现在是「**首个工具选择正确率**」，与「答案对不对」是两件事，分开报。

---

## 11. 三个子轮次

| 轮次 | 内容 | 可验证产出 | 依赖模型 |
| --- | --- | --- | --- |
| **c1** 工具层与管道骨架 | `ChunkRetriever` + `FullTextChunkRetriever` + AC 规则层 + **10 个 Tool** + `ToolResult` + `CardAssembler` + `ComplianceFilter` 接入 + 非流式 `AskPipeline` + 降级 2/3/4/5 级 | **10 个 Tool 的返回逐字段正确**；规则层命中率报表；降级 2 级演练通过 | **不需要** |
| **c2** Agent 与流式 | `ReActAgent` + `Toolkit` 装配 + **SSE 事件协议 + 11 类事件** + 生成段 + 工具结果缓存 + 成本计量 + 闸门 + 评测集 | 事件契约 6 条断言全绿；首字 ≤200ms；评测集 120 条跑通 | 需要 |
| **c3** 记忆与回流 | 记忆三层 + 知识索引与事件消费 + 未命中聚类回流 + 工具选择正确率埋点 + URGENT 旁路打磨 | 未命中闭环走通一次全链路；删除记忆后一致性检查为 0 | 部分 |

**c1 完全不碰模型、可 100% 断言**，而且它产出的 10 个 Tool 在 A 形态下**一行都不会白写**
（它们本来就要写，只是外面多包一层 Tool 描述）。
这是把 c1 排在前面的理由：不依赖你的 API Key，也不依赖任何未验证项。

---

## 12. 仍需你确认的 5 条

| # | 问题 | 我的建议 |
| --- | --- | --- |
| 1 | **总预算 3s → 10s** | 确认 10 秒；同时把验收口径「AI P95 <3s」改成 `<10s`，首字保持 ≤200ms。**这条不改口径，验收时一定对不上** |
| 2 | **URGENT 旁路** | 确认应急词命中时**不进 Agent**（800ms 直出）。这是为保住文档 6.1 的 URGENT 预算，属于对 A 的必要补丁 |
| 3 | **模型 API Key** | 需要 DashScope Key 才能录 c2 的 CI 回放集。c1 不需要，可以先动 |
| 4 | **评测集 120 条来源** | 我按 10 个工具分层自造，留「真实语料替换位」。**自造语料的 ≥92% ≠ 真实语料的 ≥92%**，必须写进验收口径 |
| 5 | **ASR** | 用微信同声传译插件（客户端转文字），不做服务端 ASR。方言字准率是文档 6.16 列的「最大技术未知数」，没测基线前不该投入 |

---

## 13. 风险与明确不做

**风险**
- 成本从「不是约束」变成「接近闸门」——需要实测校准（第 9 节）。
- 「老人能否接受 10 秒」没有用户测试数据。
- 小程序 `enableChunked` + 弱网表现**未验证**。
- 新增失效模式：工具选错（确定性管道下没有这个模式）。需要埋点。

**本轮明确不做**
- 向量检索完整链路与 Rerank 决策 → P1（文档 12.2）
- 多模态拍照识病、位置与就近排序 → P1
- 后台待办首页、一句话发布、核验四动作、未命中处理界面 → P0-d

---

## 14. 确认方式

第 12 节 5 条回「全按建议」即可，或单条否决。
第 1 条（总预算与验收口径）是最要紧的——它影响的是验收时能不能通过，不是实现难度。
