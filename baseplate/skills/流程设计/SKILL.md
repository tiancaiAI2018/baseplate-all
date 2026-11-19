---
name: 流程设计
description: 用户要创建、修改、查看审批流程或流程草稿时使用。负责把自然语言需求整理成节点与连线，再调用工作流工具
version: 1
---

# 流程设计

## 什么时候用

- 「帮我建一个请假流程」「做一个报销审批」
- 「这个流程加一个部门经理节点」「把金额大于 5000 的分支改一下」
- 「现在有哪些流程」

## 怎么做

1. 先调用 `workflow_list_definitions` 看已有流程，避免 key 重复；改草稿时先调 `workflow_get_draft`
2. 把需求整理成结构化节点：
   - 直线流程可以省略 edges，按 nodes 顺序串联
   - 有条件分支时加 `EXCLUSIVE_GATEWAY`，在 edges 上写条件（见下一节）
3. 每个审批节点都要配置 `decisionConfig.buttons`，至少两个：
   - `APPROVE`（同意）
   - `REJECT`（驳回，建议 `reasonRequired: true`）
   否则用户办理任务时看不到「驳回」按钮
4. 调用 `workflow_create_draft` 前做一次连线自检：
   - 除 START 外每个节点都要有入线
   - 除 END 外每个节点都要有出线
   - 从 START 出发能一路走到所有节点
   漏一条会报「从 START 不可达」，按报错里列的节点补连线后重试
5. 如果需求涉及「谁能看/改哪些字段」，配置 `config.fieldPolicy`（见字段权限一节）
6. 如果驳回要退到指定节点（而不是上一个），画回退 edge 并标 `rejectRoute: true`（见驳回一节）
7. 调用 `workflow_create_draft`，前端会打开设计器并回填节点配置
8. 只说到「草稿已打开」为止，不要声称已部署；部署由用户在设计器确认

## 一份流程可以有多份表单

不是每个节点各画一套表单，而是「表单定义 + 绑定 + 权限」三层：

- **流程级基础表单**：用户在流程的「发起配置」里统一设置，可多选；所有节点自动继承，
  节点端不能删除。AI 草稿里**不需要**重复声明这些编码。
- **节点新增表单**：写在节点的 `config.formCodes` 数组里（可多个），只写本节点专属的表单。
  节点实际看到的表单 = 基础表单 ∪ `formCodes`。
- 数据按 `formCode` 分桶保存（`formDataByCode`），同名字段也不会串。

```json
{
  "id": "Task_Finance",
  "name": "财务复核",
  "type": "APPROVAL",
  "config": {
    "formCodes": ["finance_review_form"],
    "fieldPolicy": {
      "expense_form": { "amount": "READONLY" },
      "finance_review_form": { "financeComment": "EDITABLE" }
    }
  }
}
```

## 字段权限怎么配

字段权限是**节点级 + 表单级**的：同一个字段，在发起人节点可编辑，在审批节点只读，在无关节点隐藏。
写在节点的 `config.fieldPolicy` 里，结构是**二维**的 `{表单编码: {字段名: 权限}}`：

```json
{
  "formCodes": ["finance_review_form"],
  "fieldPolicy": {
    "expense_form": {
      "amount": "READONLY",
      "salary": "HIDDEN"
    },
    "finance_review_form": {
      "financeComment": "EDITABLE"
    }
  }
}
```

- 取值只有三种：`EDITABLE`（可编辑）、`READONLY`（能看不能改）、`HIDDEN`（看不到）
- 没列出来的字段默认是 `EDITABLE`，所以只需写出非可编辑的字段
- 第一层键必须是真实表单编码（基础表单或该节点 `formCodes` 里的编码），
  第二层字段名必须是该表单里真实存在的字段：先调 `form_get_draft` 看字段清单，
  **绝不臆造表单编码和字段名**。写错不会报错，但也不会生效
- 常见模式：
  - 发起人填、审批人只看 → 该节点把这张表的这个字段设 `READONLY`
  - 敏感信息（薪资、身份证）→ 对不需要看的节点设 `HIDDEN`
  - 审批意见这类专属字段 → 建一张独立表单绑在审批节点的 `formCodes` 上，发起人节点看不到
- 后端会强制校验：设成 READONLY/HIDDEN 的字段，即使前端伪造提交也改不了；
  前端的置灰和隐藏只是体验。不要把必填字段设成 HIDDEN，否则该字段在本节点没法填写

## 条件怎么写

连线条件是一个表达式，支持组合逻辑：

```
${amount >= 5000}
${amount >= 5000 && dept == 'SALES'}
${(amount >= 5000 || urgent == true) && dept != 'FINANCE'}
```

- 布尔运算：`&&`、`||`、`!`，优先级不确定时用括号
- 比较：`==`、`!=`、`>`、`>=`、`<`、`<=`
- 字符串用单引号（`'SALES'`），数字不加引号（`5000`），布尔写 `true` / `false`
- 兜底分支：留空 `condition` 并标 `default: true`，表示所有条件都不满足时走这条
- 暂不支持脚本条件（服务端会拒绝带 `language` 属性的表达式），复杂判断请用表达式组合

## 驳回目标可以在图上画出来

驳回不再是写死的「退上一个节点」：可以在图上画**驳回路由**来决定退到哪。

在 edges 里从审批节点画一条指回前面审批节点的线，并标 `rejectRoute: true`：

```json
{
  "from": "Task_Finance",
  "to": "Task_Manager",
  "name": "天数超标退回主管",
  "condition": "${days > 5}",
  "rejectRoute": true
}
```

- 用户点驳回时，只求值带 `rejectRoute` 的线，命中哪条就退到那条线指向的节点
- `condition` 省略表示无条件命中；多条命中时按画线的顺序取第一条，所以条件尽量互斥
- 没画驳回路由、或所有条件都没命中，才兜底退回**上一个已完成的审批节点**
- 驳回路由的 condition **只在驳回时求值**，正常审批不会沿它绕回去（编译期自动加守卫），
  所以不用担心画出环会让流程正向流转死循环
- 不要把普通正向连线也标成 `rejectRoute: true`，那会让它在正常审批时完全不生效
- 条件可用发起/审批时提交的表单字段，如 `${days > 5}`、`${amount >= 5000 && dept == 'SALES'}`

## 边界

- 不编造审批人：不知道找谁就问，或用 `DEPT_MANAGER`
- 用户没提的节点不要自己加
- 不编造表单编码和字段名：配 `formCodes`、`fieldPolicy`、网关条件、FORM_FIELD 找人前，
  先确认表单和字段真实存在
- 基础表单由用户在发起配置里维护，草稿里不要重复声明；只需要写本节点新增的 `formCodes`
