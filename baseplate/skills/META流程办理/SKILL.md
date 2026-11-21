---
name: META流程办理
description: META 模式下查待办、我发起的流程、进度、已办与汇总。重点是先用工具定位再展开、理解 UI 卡片，且不代替用户审批。
version: 1
modes: meta
---

# META 流程办理

数据都是用户自己的，服务端按角色和参与关系校验范围。
工具调用一律通过 `tool_call`，语法见 META工具检索 技能。

## 意图 → 工具

| 用户说 | 工具 |
|---|---|
| 我有哪些待办、要我审批的 | `workflow_list_my_tasks` |
| 我发起了哪些、我提交的单子 | `workflow_list_my_started` |
| 这单走到哪、卡在谁那里 | `workflow_instance_progress` |
| 我审批过什么 | `workflow_list_my_done` |
| 我还有多少事没处理 | `workflow_my_summary` |
| 这单要填什么、之前填了什么 | `workflow_task_detail` |

## 典型串联（元工具）

先定位再展开，分两轮调用：

```
tool_call {"code": "workflow_list_my_started", "arguments": {"state": "RUNNING"}}
```

从返回里拿到 `instanceId` 后再第二轮：

```
tool_call {"code": "workflow_instance_progress", "arguments": {"instanceId": "<上一步拿到的值>"}}
```

用户直接给了业务单号时，跳过列表；若返回 `ambiguous=true`，把候选列给用户选，不替他挑。

## 业务规则

- 默认只看未走完的：不传 `state` 即 RUNNING；用户明确问「历史」「全部」才用 FINISHED / ALL。
- 数字类问题用 `workflow_my_summary`，它渲染成四个数字卡片，不必逐条拉列表。
- `workflow_instance_progress` 会返回 `ui` 进度图（绿=已走过，橙=当前，红=被打回），
  前端会画出来；正文只补一句结论，不要复述 JSON 或画流程图。
- `workflow_task_detail` 返回的 `ui` 会渲染任务卡片和表单字段，回答时只说重点。

## 边界

- 不代替用户审批：没有同意/驳回工具是刻意的，审批必须由本人在办理抽屉确认。
  用户说「帮我批了」时说明原因并引导他点办理按钮。
- 不编造单号、实例 id、节点名；查不到就如实说，不换 id 反复试。
- 不把 `ui` 当业务数据；回答用 `status`、`currentNodes`、`tasks` 这些字段。
