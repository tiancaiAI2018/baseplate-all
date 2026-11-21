---
name: CLI表单设计
description: CLI 模式下设计流程表单、增删字段或调整字段类型。先查现有草稿再生成字段清单，最后通过 CLI 打开设计器。
version: 1
modes: cli
---

# CLI 表单设计

工具调用走 `baseplate` 的 `call`，语法见 CLI工具检索 技能。
`form_create_draft` 是外部工具，`call` 它会请求前端打开表单设计器。

## 什么时候用

- 「设计一个请假申请表单」「报销单要有哪些字段」
- 「给这个表单加一个附件上传」

## 串联流程（CLI）

1. 改已有表单时，先读草稿拿现有字段和规则：
   ```
   call form_get_draft {"formCode": "leave_form"}
   ```
   不确定 formCode 时先 `call form_list_definitions {}` 或从流程草稿里找。
2. 把需求整理成字段清单：
   - 字段名用小写英文＋下划线，如 `leave_days`
   - 类型只用 input / textarea / number / slider / date / datetime / switch / select / radio / checkbox / upload / editor
   - select / radio / checkbox 必须给 options
   - 字段名要稳定：流程里的找人、网关条件、字段权限都按字段名匹配
3. 通过 CLI 打开设计器：
   ```
   call form_create_draft {"formCode": "leave_form", "fields": [...]}
   ```
4. 只说到「草稿已打开」为止，发布由用户点「保存并发布」。

## 字段权限由流程节点决定

字段的只读 / 隐藏 / 可编辑不在表单里设置，而是流程节点按需要配的。
`config.fieldPolicy` 是二维结构 `{表单编码: {字段名: EDITABLE/READONLY/HIDDEN}}`。
例如「薪资字段普通审批人看不到」：

1. 本技能里照常建 `salary` 字段（表单只定义字段长什么样）
2. 提示用户到流程设计器把该节点这张表的 `salary` 设成 HIDDEN，
   或让 CLI流程设计 技能配 `{"expense_form": {"salary": "HIDDEN"}}`

不要拒绝建字段，也不要在表单字段上加只读/隐藏属性——表单层没有这个能力。

## 一份流程可以有多份表单

流程级基础表单由用户在发起配置里统一设置，所有节点继承；
节点专属表单写在节点 `config.formCodes` 里。表单设计技能只产出字段清单，
绑定和权限由 CLI流程设计 技能负责。

## 边界

- 字段名不能用 script / eval / expression / class 等保留词
- 工具失败时按原因修正参数重试，不要直接结束回复
