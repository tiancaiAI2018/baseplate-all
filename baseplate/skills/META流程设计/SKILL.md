---
name: META流程设计
description: META 模式下创建、修改审批流程。把自然语言整理成节点与连线，用元工具先查目录与草稿，再通过外部工具打开设计器。
version: 1
modes: meta
---

# META 流程设计

工具调用通过 `tool_call`，语法见 META工具检索 技能。
`workflow_create_draft` 是外部工具，`tool_call` 它会请求前端打开流程设计器。

## 什么时候用

- 「帮我建一个请假流程」「做一个报销审批」
- 「这个流程加一个部门经理节点」「把金额大于 5000 的分支改一下」

## 串联流程（元工具）

1. 先看已有流程避免 key 重复；改草稿先读现有节点：
   ```
   tool_call {"code": "workflow_list_definitions", "arguments": {}}
   tool_call {"code": "workflow_get_draft", "arguments": {"processKey": "leave_approval"}}
   ```
2. 把需求整理成结构化节点：
   - 直线流程可省略 edges，按 nodes 顺序串联
   - 有条件分支加 `EXCLUSIVE_GATEWAY`，在 edges 写条件
3. 每个审批节点配 `decisionConfig.buttons`，至少 `APPROVE` 和 `REJECT`
   （建议 REJECT 设 `reasonRequired: true`），否则办理时看不到驳回按钮
4. 连线自检：除 START 外每个节点有入线，除 END 外每个节点有出线，START 可达全部节点
5. 涉及字段权限时先 `tool_call {"code": "form_get_draft", "arguments": {"formCode": "..."}}`
   确认真实字段，再配 `config.fieldPolicy`
6. 通过元工具打开设计器：
   ```
   tool_call {"code": "workflow_create_draft", "arguments": {"processKey": "...", "nodes": [...], "edges": [...]}}
   ```
7. 只说到「草稿已打开」为止，不要声称已部署

## 表单与字段权限（业务理解）

- 流程级基础表单在发起配置里统一设置，草稿里不要重复声明
- 节点专属表单写在节点 `config.formCodes` 里；节点实际看到的表单 = 基础表单 ∪ formCodes
- `config.fieldPolicy` 是二维结构 `{表单编码: {字段名: EDITABLE/READONLY/HIDDEN}}`：
  - 没列出来的字段默认 EDITABLE
  - 第一层键必须是真实表单编码，第二层字段名必须先 `form_get_draft` 确认；写错不会报错但也不生效
  - 不要把必填字段设成 HIDDEN
- 敏感字段（薪资、身份证）对不需要看的节点设 HIDDEN；审批意见可建独立表单绑在审批节点

## 条件与驳回路由

- 条件写法：`${amount >= 5000}`、`${amount >= 5000 && dept == 'SALES'}`，
  支持 `&& || !` 和 `== != > >= < <=`；字符串用单引号，数字不加引号
- 兜底分支留空 `condition` 并标 `default: true`
- 驳回可画线指回前面的审批节点并标 `rejectRoute: true`，
  `condition` 省略表示无条件命中，多条命中取第一条
- 普通正向连线不要标 `rejectRoute: true`

## 边界

- 不编造审批人，不知道就问或用 `DEPT_MANAGER`
- 用户没提的节点不要自己加
- 不臆造表单编码和字段名：配 formCodes / fieldPolicy / 网关条件 / FORM_FIELD 找人前先确认真实存在
