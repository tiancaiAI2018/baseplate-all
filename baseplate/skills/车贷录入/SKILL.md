---
name: 车贷录入
description: 把「待分析资料」变成车贷申请单的标准流程。先用 loan.draft.prepare 抽取骨架，再补待填字段、化解冲突，最后 loan.validate 预检、loan.draft.save 保存草稿（只存草稿，提交由人点按钮）。任何情况下都不要自己编造资料里没有的字段。本技能 skillId="车贷录入_baseplate"。
version: 1
modes: cli,meta
---

# 车贷录入（资料 → 申请单）

面向「用户从微信/相册丢来一堆资料，AI 帮他录成车贷申请单」的场景。
**目标是最少轮次录出一份能过校验的草稿**，而不是跟用户一问一答挤牙膏。

## 工具（都在 `baseplate` 的 script 里，按需 `call`）

| 工具 | 用途 | 何时用 |
|---|---|---|
| `loan.draft.prepare` | 确定性抽取草稿骨架（已填字典编码），返回 `pendingFields` + `conflicts` | **第一步永远调它** |
| `loan.products` | 合法产品编码 | 需要确认 productType 时 |
| `loan.schema` | 字段清单/字典/联动 | 需要知道某个字段字典编码时 |
| `loan.catalog` | 品牌/车系/车型/年款 | prepare 没识别出车型时 |
| `loan.validate` | 预检，返回字段级错误 | **保存前必须跑一次** |
| `loan.draft.save` | 保存草稿（外部工具，浏览器执行） | 跑完 validate 后就存，哪怕还有 error；只有"编数值才能过"才别存 |
| `loan.submit` | 最终提交（approval=ASK） | **用户明确说"提交"才用** |

## 标准流程（最多 4 轮工具调用）

```
1) call loan.draft.prepare {"materialIds":["MAT-xxx","MAT-yyy"]}
   → 返回 { draft, pendingFields, conflicts, productType }
2) 看 conflicts：有多值冲突（金额/车价/期数）→ 先问用户，别自己选
3) 看 pendingFields：把用户能给的补进 draft；补不了的保持原样（留空）
4) call loan.validate {"draft": draft} → 有 error 就按 path 修，最多修 2 轮
5) call loan.draft.save {"draft": draft} → 回报草稿号，让用户去「AI 草稿」审核提交
```

> **第 5 步一定要做**：即使 `loan.validate` 还有 error（字段没填全、LTV 超限），
> **也要先把草稿存下来**——用户就是去「AI 草稿」页面补齐和修改的。
> 只有"编造数值才能过校验"时才不要存，并且必须在回复里说清还差什么。

一次 `script` 里可以连写多行，按顺序执行：

```
call loan.draft.prepare {"materialIds":["MAT-abc"]}
call loan.validate {"draft":{...}}
```

## 硬规则

1. **不编字段**：资料里没有的字段一律留在 `pendingFields`，不许填默认值（电池健康度、居住年限、邮编尤其别瞎填）。
2. **不猜字典码**：`gender=男` 要写 `M`；行业"硬件"→`IT`；关系"老婆"→`SPOUSE`。字典不对，校验一定红。
3. **不擅自改金额**：资料里出现两个金额（落地价 vs 合同价、21 万 vs 21.5 万）就是冲突，必须问用户"以哪个为准"。
4. **不合规不硬存**：LTV 超限、车龄超限、联系人不够、缺担保人 → 明确告诉用户差多少、怎么改，别偷偷改数字让它通过。
5. **只存草稿**：`loan.draft.save` 是安全的；`loan.submit` 是不可逆动作，只有用户明确要求时才用。
6. **别反复试**：同一份草稿 `loan.validate` 最多跑 2 轮；还是红的就把错误原样列给用户，不要继续猜。

## 输出风格

- 先给结论（能不能录、缺什么），再给细节。
- 用 Markdown 表格列待补字段：`字段 | 为什么缺 | 建议怎么补`。
- 冲突单独一段，问用户选哪个。
- 末尾给下一步：让用户去「AI 草稿」菜单审核提交。
