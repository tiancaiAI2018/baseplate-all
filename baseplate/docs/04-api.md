# 04 · REST 接口清单

## 本文档解决什么

「这个能力对外暴露了哪些接口、要什么权限」。共 **165 个方法级映射**，按模块分组。
改动任何端点后，必须同步更新本表（见 `INDEX.md` 第六节）。

---

## 一、通用约定

### 请求

| 项 | 约定 |
|---|---|
| 鉴权头 | `Authorization: Bearer <jwt>`（内部接口） |
| 追踪 | 可选带 `X-Trace-Id`，不带则服务端生成；响应不回传，看日志用 |
| 内容类型 | `application/json` |
| 分页参数 | `page`（从 1 开始）、`size`（默认 20，上限各接口自定） |
| 时间格式 | 请求与响应均为 ISO-8601 带时区，如 `2026-11-10T16:45:45.848Z` |

### 响应

成功：直接返回资源或 `{ items, total }` 分页结构，无统一包裹层。

失败：**RFC 9457 ProblemDetail**

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "缺少权限：user:create",
  "code": "FORBIDDEN",
  "traceId": "888a0fd58cf14a74bbb7052631c606c7",
  "path": "/api/identity/users"
}
```

### 错误码

| code | HTTP | 含义 |
|---|---|---|
| `AUTH_FAILED` | 401 | 未认证、令牌无效/过期/已注销 |
| `PASSWORD_CHANGE_REQUIRED` | 403 | 首登或密码过期，必须先去改密 |
| `FORBIDDEN` | 403 | 已认证但缺权限 |
| `BAD_REQUEST` | 400 | 参数不合法、请求体解析失败、业务校验不通过 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `CONFLICT` | 409 | 唯一约束冲突、nonce 重放 |
| `RATE_LIMITED` | 429 | 触发限流 |
| `INTERNAL_ERROR` | 500 | 未预期异常，看 `traceId` 查日志 |

> 业务校验失败统一走 `IllegalArgumentException` → `400 BAD_REQUEST`，`detail` 就是给前端看的原话。
> 不要自己 `new RuntimeException("...")`，那会变成 500「服务内部错误」。

---

## 二、认证 `/api/auth`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 入参 `username/password/tenant`；返回 `accessToken/expiresIn/mustChangePassword` |
| POST | `/api/auth/logout` | 登录即可 | 把当前 jti 加黑名单 |
| GET | `/api/auth/me` | 登录即可 | 当前用户 + 角色 + 权限集合 |
| POST | `/api/auth/password` | 登录即可 | 改自己的密码；成功后该用户所有令牌失效 |

登录失败 5 次锁 15 分钟（可配）。密码策略：≥10 位、含大小写与数字/符号、**不能包含用户名**。

---

## 三、用户 `/api/identity/users`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/identity/users` | `user:view` | 分页 + `keyword` 搜索 + 数据范围过滤；返回 `mustChangePassword` / `failedAttempts` / `lockedUntil`，管理员能看出账号是否被锁 |
| POST | `/api/identity/users` | `user:create` | 创建；初始密码可指定或随机生成 |
| PUT | `/api/identity/users/{id}` | `user:update` | 改资料、状态 |
| DELETE | `/api/identity/users/{id}` | `user:delete` | 逻辑停用；不能停用自己 |
| POST | `/api/identity/users/{id}/reset-password` | `user:reset` | 返回**一次性明文**，置 `must_change_password`，并**顺带解锁** |
| POST | `/api/identity/users/{id}/unlock` | `user:reset` | 只清失败计数与 `locked_until`，不动密码与首登标记 |
| POST | `/api/identity/users/{id}/roles` | `role:assign` | 覆盖式分配角色 |

## 四、角色 `/api/identity/roles`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/identity/roles` | `role:view` | |
| POST | `/api/identity/roles` | `role:create` | |
| PUT | `/api/identity/roles/{id}` | `role:update` | |
| DELETE | `/api/identity/roles/{id}` | `role:delete` | 内置角色不可删 |
| PUT | `/api/identity/roles/{id}/permissions` | `role:assign` | 覆盖式设置权限编码列表 |

## 五、组织 `/api/identity/orgs`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/identity/orgs` | `org:view` | 返回树（`path` 字段做层级） |
| POST | `/api/identity/orgs` | `org:create` | |
| PUT | `/api/identity/orgs/{id}` | `org:update` | |
| DELETE | `/api/identity/orgs/{id}` | `org:delete` | 有子节点或有人时拒绝 |

## 六、权限目录与菜单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/identity/permissions` | 登录即可 | 返回 `{ groups, modules, manage }`：分组树 / 模块列表 / 管理列表（含停用） |
| POST | `/api/identity/permissions` | `role:assign` | 新增自定义权限点；`code` 格式 `[A-Za-z0-9._:-]{3,64}` |
| PUT | `/api/identity/permissions/{code}` | `role:assign` | 改中文名与类型；置 `label_edited`，重启不覆盖 |
| DELETE | `/api/identity/permissions/{code}` | `role:assign` | **仅自定义可删**；模块声明的返回 400 |
| POST | `/api/identity/permissions/{code}/enabled` | `role:assign` | `?value=true/false`；停用后不进角色勾选树 |
| PUT | `/api/identity/modules/{moduleId}` | `role:assign` | 改模块中文名（覆盖清单里的 `display-name`） |
| GET | `/api/identity/menus` | 登录即可 | 当前用户可见菜单（**直接读模块清单，未落库**） |

---

## 七、审计 `/api/audit`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/audit/access` | `audit:view` | 访问审计；筛选 `actor/path/status/source`，`source` 取 `INTERNAL`/`THIRD_PARTY` |
| GET | `/api/audit/access/{id}` | `audit:view` | 详情：IP、UA、耗时、查询串、扩展 JSON、应用名/API 编码 |
| GET | `/api/audit/business` | `audit:view` | 业务审计；筛选 `action` |
| GET | `/api/audit/business/{id}` | `audit:view` | 详情：变更前/后 JSON |
| GET | `/api/audit/logins` | `audit:view` | 登录记录；筛选 `username/success` |

---

## 八、开放平台 · 应用管理 `/api/platform/apps`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/platform/apps` | `openapi:view` | 列表，含 `grantTypes`、`redirectUris`、`grantedApiCount` |
| POST | `/api/platform/apps` | `openapi:create` | 创建；返回 `clientSecret` 与 `signingSecret` 明文**仅此一次** |
| PUT | `/api/platform/apps/{id}` | `openapi:update` | 改配置。关掉 `authorization_code` 会连带撤销该应用全部刷新令牌 |
| DELETE | `/api/platform/apps/{id}` | `openapi:update` | 软删（`status=DELETED`），日志保留 |
| POST | `/api/platform/apps/{id}/rotate` | `openapi:rotate` | 轮换双密钥；旧密钥与已签发刷新令牌立即失效 |
| GET | `/api/platform/apps/{id}/apis` | `openapi:view` | 返回 `{ appKey, appName, grantTypes, catalog, granted }` |
| PUT | `/api/platform/apps/{id}/apis` | `openapi:update` | 覆盖式授权。**应用没开通授权码时勾 USER 接口 → 400** |
| GET | `/api/platform/apps/{id}/logs` | `openapi:log` | 调用日志，`limit` 默认 50 |
| GET | `/api/platform/apps/{id}/logs/{logId}` | `openapi:log` | 单条详情 |
| GET | `/api/platform/apps/{id}/usage` | `openapi:view` | 按天聚合，`days` 默认 7 |
| POST | `/api/platform/apps/{id}/sandbox-test` | `openapi:view` | 服务端生成待签串与签名并自校验，返回 curl |

## 九、开放平台 · 接口目录 `/api/platform/apis`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/platform/apis` | `openapi:view` | `{ items, groups }`；`items` 含停用条目，带 `subject/source/enabled` |
| POST | `/api/platform/apis` | `openapi:update` | 手工登记；`source=MANUAL`；与注解冲突返回 400 |
| PUT | `/api/platform/apis/{code}` | `openapi:update` | 只改 `name/groupName/description`；置 `label_edited` |
| POST | `/api/platform/apis/{code}/enabled` | `openapi:update` | `?value=true/false`；停用后鉴权链直接 404 |
| DELETE | `/api/platform/apis/{code}` | `openapi:update` | **仅 MANUAL 可删**；注解声明的返回 400 |

## 十、开放平台 · 授权页 `/api/platform/oauth`

供控制台前端调，**不要求 `openapi:*` 权限**——授权主体是普通用户。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/platform/oauth/authorize/context` | 登录即可 | 参数 `clientId/scope/redirectUri`；返回应用名、scope 中文说明、回调是否合法、是否授权过 |
| POST | `/api/platform/oauth/authorize` | 登录即可 | 用户同意/拒绝；返回 `{ redirectUrl }`，前端直接 `location.href` |
| GET | `/api/platform/oauth/grants` | 登录即可 | 当前用户已授权的应用清单 |
| POST | `/api/platform/oauth/revoke` | 登录即可 | 撤回对某应用的授权（刷新令牌全撤） |

## 十一、开放平台 · 对外接口 `/openapi/v1`

**这一组走独立鉴权链，不走 `/api` 的 JWT。** 详见 [06-security.md](06-security.md)。

| 方法 | 路径 | 主体 | 说明 |
|---|---|---|---|
| POST | `/openapi/v1/oauth/token` | — | 换令牌。`grant_type` 可为 `client_credentials` / `authorization_code` / `refresh_token` |
| GET | `/openapi/v1/ping` | `CLIENT` | 连通性探活 |
| GET | `/openapi/v1/whoami` | `CLIENT` | 返回应用、租户、scope、主体、被代理用户 |
| GET | `/openapi/v1/userinfo` | **`USER`** | 返回被代理用户身份与角色；机器令牌 → `403 SUBJECT_REQUIRED` |
| POST | `/openapi/v1/echo` | `CLIENT` | 回显 + 幂等示例（`Idempotency-Key`） |

### 对外开放的智能体接口

以下接口同样是开放 API（签名 + 开放令牌），`subject=USER`，只认授权码换来的带 uid 令牌。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/openapi/agent/chat/stream` | `agent:chat` | SSE 对话；body `{agentCode, pageKey, sessionId, message}` |
| GET | `/openapi/agent/chat/bootstrap` | `agent:chat` | 判断指定页面的 Agent 是否启用 |
| GET | `/openapi/agent/sessions` | `agent:session:view` | 会话列表 |
| GET | `/openapi/agent/sessions/{id}/messages` | `agent:session:view` | 会话消息 |
| POST | `/openapi/agent/external-calls/{callId}/result` | `agent:chat` | 外部工具结果回传，返回 SSE 恢复模型 |
| POST | `/openapi/integration/proxy/{serviceCode}/{endpointCode}` | `agent:chat` | 开放平台外部工具执行，服务端网关注入第三方凭证 |

---

## 十二、工作流 `/api/workflow`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/workflow/definitions` | `wf:deploy` | 部署 BPMN 2.0 XML；`strategy=KEEP_OLD/MIGRATE_ALL` 决定老实例是否迁移到新版本 |
| GET | `/api/workflow/definitions` | `wf:view` | **全部版本**（不是只给 latest），带每版运行中/已结束实例数与挂起态 |
| GET | `/api/workflow/overview` | `wf:view` | 概览数字：定义数、运行中/已结束实例数、已挂起定义数；首页用这个，不要为拿数字拉全量明细 |
| GET | `/api/workflow/catalog` | `wf:task` | 「发起流程」目录：最新已发布、未挂起、且当前用户有权发起的流程，附发起表单 schema |
| GET | `/api/workflow/definitions/{processDefinitionId}` | `wf:view` | 定义详情：版本历史、实例统计、节点清单 |
| GET | `/api/workflow/definitions/{processDefinitionId}/xml` | `wf:view` | 可渲染的 BPMN XML；无 DI 的存量流程自动补坐标 |
| POST | `/api/workflow/definitions/{processDefinitionId}/suspend` | `wf:manage` | 作废：只挂起定义、不挂起实例，老实例可走完 |
| POST | `/api/workflow/definitions/{processDefinitionId}/activate` | `wf:manage` | 恢复启用 |
| GET | `/api/workflow/definitions/migrate/preview` | `wf:manage` | 迁移预检：`from`/`to` 两个定义 ID，返回不兼容清单（不写数据） |
| POST | `/api/workflow/definitions/migrate` | `wf:manage` | 保留当前节点，把源版本下全部运行中实例迁到目标版本；整体拒绝策略 |
| POST | `/api/workflow/instances` | `wf:task` | 发起；自动注入 `initiator=当前用户 ID` |
| GET | `/api/workflow/instances` | `wf:task` | 旧列表接口（兼容），最多 100 条 |
| GET | `/api/workflow/instances/page` | `wf:task` | 分页：`page/size/state/processKey/keyword`，`total` 是真总数 |
| GET | `/api/workflow/instances/{processInstanceId}/detail` | `wf:view` | **运行中/已结束通用详情**：轨迹、变量、操作流水、原因、打回次数、图高亮节点 |
| GET | `/api/workflow/instances/{processInstanceId}/xml` | `wf:view` | 实例流程图 XML + 已完成/当前/被打回三组节点 ID |
| POST | `/api/workflow/instances/{processInstanceId}/terminate` | `wf:manage` | 终止运行中实例，原因写入流水 |
| POST | `/api/workflow/instances/{processInstanceId}/withdraw` | `wf:task` | 发起人撤回自己还在运行中的实例；管理员可代撤 |
| GET | `/api/workflow/instances/{processInstanceId}/tasks` | `wf:task` | 旧接口：只返回**运行中**任务，已结束实例为空；详情请用 `/detail` |
| GET | `/api/workflow/tasks` | `wf:task` | 按 `assignee` 查任务（candidateOrAssigned，含加签人） |
| GET | `/api/workflow/tasks/todo` | `wf:task` | 当前登录人的待办 |
| GET | `/api/workflow/tasks/done` | `wf:task` | 我的已办：我办理过的历史任务，按办结时间倒序 |
| GET | `/api/workflow/tasks/{taskId}/detail` | `wf:task` | 办理详情：表单 schema、已填数据、决策按钮、可回退节点；`canHandle` 由服务端按归属判定 |
| POST | `/api/workflow/tasks/{taskId}/complete` | `wf:task` | 办理；`decision=APPROVE/REJECT`，驳回原因写进流水并计入打回次数 |
| POST | `/api/workflow/tasks/{taskId}/add-sign` | `wf:task` | 加签：改成候选任务，被加签人能在待办里看到 |
| POST | `/api/workflow/tasks/{taskId}/transfer` | `wf:task` | 转办 |
| POST | `/api/workflow/tasks/{taskId}/rollback` | `wf:rollback` | 回退到指定节点；校验节点属于该定义，原因写进流水 |
| GET | `/api/workflow/node-configs` | `wf:view` | 节点结构化配置；`processKey` 必填，`version=0` 表示取最新已部署版本 |
| PUT | `/api/workflow/node-configs` | `wf:config` | 保存节点配置草稿（找人/签署/表单/决策/触发/条件），部署时由服务端编译进 BPMN |
| GET | `/api/workflow/node-configs/options` | `wf:view` | 属性面板下拉数据：找人类型、签署类型、角色、组织、已发布表单、运算符、触发动作 |
| GET | `/api/workflow/process-settings` | `wf:config` | 发起侧配置：发起范围、默认表单等 |
| PUT | `/api/workflow/process-settings/{processKey}` | `wf:config` | 保存某流程的发起配置 |
| GET | `/api/workflow/assignee-options` | `wf:task` | 转办/加签/发起范围的人员选项，只暴露最小字段 |
| GET | `/api/workflow/instances/{processInstanceId}/form-data` | `wf:view` | 该实例各节点提交的表单数据（含租户校验） |
| GET | `/api/workflow/widgets` | `wf:view` | 组件素材库（可复用表单片段） |
| POST | `/api/workflow/widgets` | `wf:form` | 保存自定义组件素材，供设计器插入或别的页面单独渲染 |
| POST | `/api/workflow/lab/init` | `wf:lab` | 幂等初始化演练环境：组织/角色/6 个测试账号/表单/演示流程/节点配置 |
| GET | `/api/workflow/lab/status` | `wf:lab` | 演练环境状态、账号清单、演示流程版本与隔离验证租户 |

### 分单中心 `/api/workflow/dispatch`

组织池是硬边界：候选只从池配置的组织（可选含下级）解析；候选为空时任务进待派池并告警，不回退发起人。
签到 ≠ 在线：签到决定候选资格，心跳决定能否实时推送。拒绝事实、惩罚、评价全部按租户隔离。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/workflow/dispatch/checkin/today` | `wf:checkin` / `wf:task` | 今日签到、签退、坐席状态与最近 7 天记录 |
| POST | `/api/workflow/dispatch/checkin` | `wf:checkin` / `wf:task` | 签到上班；当天幂等 |
| POST | `/api/workflow/dispatch/checkin/checkout` | `wf:checkin` / `wf:task` | 签退下班 |
| POST | `/api/workflow/dispatch/checkin/heartbeat` | `wf:checkin` / `wf:task` | 坐席心跳，`status=ONLINE/BUSY/AWAY/OFFLINE` |
| GET | `/api/workflow/dispatch/pools` | `wf:pool` | 池列表（含组织与含下级标记） |
| POST | `/api/workflow/dispatch/pools` | `wf:pool` | 新建/按 code 更新池；`orgs` 为空表示禁止派单 |
| PUT | `/api/workflow/dispatch/pools/{id}` | `wf:pool` | 修改池信息与组织范围 |
| DELETE | `/api/workflow/dispatch/pools/{id}` | `wf:pool` | 停用池；已进池任务仍可人工处理 |
| GET | `/api/workflow/dispatch/orgs` | `wf:pool` | 组织树扁平列表（id/parentId/name/path） |
| GET | `/api/workflow/dispatch/assignable-users` | `wf:pool` | 池组织范围内可指派用户 |
| GET | `/api/workflow/dispatch/options` | `wf:view` | 设计器可选项：池、模式、算法、过滤器、拒绝原因 |
| GET | `/api/workflow/dispatch/policies` | `wf:view` | 某流程的节点分单策略；`definitionVersion=0` 为默认 |
| PUT | `/api/workflow/dispatch/policies` | `wf:config` | 保存节点策略：池/模式/算法/权重/拒绝策略 |
| GET | `/api/workflow/dispatch/pool-tasks` | `wf:task` | 当前用户组织池内 OPEN 任务 |
| GET | `/api/workflow/dispatch/my-tasks` | `wf:task` | 当前用户名下的分单任务 |
| POST | `/api/workflow/dispatch/pool-tasks/{id}/claim` | `wf:task` | 抢单：条件 UPDATE + Flowable claim 同事务 |
| POST | `/api/workflow/dispatch/tasks/{taskId}/assign` | `wf:dispatch` | 管理员指派；跨组织需 `wf:dispatch:cross-org` |
| POST | `/api/workflow/dispatch/tasks/{taskId}/back-to-pool` | `wf:dispatch` | 已指派任务退回池里 |
| POST | `/api/workflow/dispatch/tasks/{taskId}/refuse` | `wf:task` | 拒绝：`OFFER_DISMISS` / `ASSIGN_REFUSAL` / `RELEASE` |
| GET | `/api/workflow/dispatch/refusals` | `wf:view` | 拒绝记录；普通用户只能查自己 |
| GET | `/api/workflow/dispatch/penalties` | `wf:view` | 惩罚记录（冷却/封禁/降权） |
| POST | `/api/workflow/dispatch/penalties/{id}/revoke` | `wf:dispatch:revoke` | 撤销惩罚并重算权重 |
| GET | `/api/workflow/dispatch/ratable-tasks` | `wf:task` | 已办结且当前用户未评价的任务 |
| POST | `/api/workflow/dispatch/tasks/{taskId}/rate` | `wf:rating` | 评价 1~5 分；终态才可评，禁止自评 |
| GET | `/api/workflow/dispatch/ratings` | `wf:view` | 评价记录 |
| GET | `/api/workflow/dispatch/stats` | `wf:view` | 看板统计：待抢、今日派出/认领/拒绝、平均等待 |
| GET | `/api/workflow/dispatch/logs` | `wf:view` | 决策日志：候选人、打分、淘汰原因 |
| POST | `/api/workflow/dispatch/timeout-scan` | `wf:manage` | 手动触发超时扫描；定时任务默认每分钟执行 |

**待派任务的两种流转触发**（互不替代）：

| 层 | 触发 | 行为 | 配置 |
|---|---|---|---|
| 实时 | 坐席签到、心跳转 ONLINE（`AgentOnlineEvent`，事务提交后） | 立即重派该用户组织可见池里的 OPEN 任务 | `baseplate.workflow.dispatch.checkin-redispatch-enabled`（默认 `true`） |
| 兜底 | 定时扫描（默认每分钟） | 待派超时（默认 30 分钟）重派、已指派办理超时回池 | `pending-timeout-minutes`、`handle-timeout-minutes`、`scan-interval-ms`、`timeout-scan-enabled` |

实时层让「发起时全员未签到、后来有人上线」的积压任务立刻流转，不必等 30 分钟兜底扫描；
事件丢失也不影响最终结果，因为兜底扫描会补上。关闭实时层：`checkin-redispatch-enabled=false`。

### 流程草稿 `/api/workflow/drafts`

AI 工具创建草稿、设计器载入草稿都走这一组。草稿只存节点/连线，不部署。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/workflow/drafts` | `wf:deploy` | 创建草稿：`processKey/name/description/nodes/edges`；服务端归一化连线并编译 BPMN，返回 `id/bpmnXml/spec` |
| GET | `/api/workflow/drafts` | `wf:view` | 最近草稿列表，`limit` 默认 20 |
| GET | `/api/workflow/drafts/{id}` | `wf:view` | 草稿详情：`processKey/name/description/bpmnXml/spec` |

`edges.condition` 支持 `&& || !` 与括号（例如 `${amount >= 5000 && dept == 'SALES'}`）；
字符串用单引号、数字不加引号；有条件的分支不要标 `isDefault`。

### 结构化节点配置怎么变成 BPMN

页面上只提供**结构化字段**，不让人写表达式或脚本，服务端在部署时编译：

| 页面配置 | 编译结果（`NodeConfigCompiler`） |
|---|---|
| 找人逻辑 `DEPT_MANAGER` / `ROLE` / `USER` / `ORG_ROLE` / `PREV_OPERATOR` / `FORM_FIELD` | `AssigneeResolver` 在发起和每次办理前算出成员，写进变量 `wf_assignee_<节点>`（单签）或 `wf_assignees_<节点>`（多实例） |
| 签署方式 `SINGLE` / `OR_SIGN` / `COUNTERSIGN` / `SEQUENTIAL` | `SINGLE` → `flowable:assignee`；`OR_SIGN` → 候选组；`COUNTERSIGN` / `SEQUENTIAL` → `multiInstanceLoopCharacteristics`，通过比例写成 `completionCondition`（默认 100%） |
| 绑定表单 | `flowable:formKey`，办理时 `formData` 落 `wf_form_data` |
| 决策按钮（同意/驳回 + 是否必填原因 + 最少字数） | `wf_node_config.decision_config`，办理时校验，不通过就 400 |
| 出线条件（字段 + 运算符 + 值） | `sequenceFlow` 上的 `${field op value}`，字段名走 `[A-Za-z_][A-Za-z0-9_]{0,63}` 白名单，字符串值禁 `'` `$` `}` |
| 出线条件（表达式模式） | 直接写 `${amount >= 5000 && (dept == 'SALES' || urgent == true)}`，支持 `&& || !` 与括号；禁止带 `language=` 的脚本表达式 |

节点配置按 `(tenant, processKey, version)` 落库，所以**部署新版本不会改到老版本实例的行为**。
部署接口返回 `version`，前端/脚本可直接用它去查刚存的那套配置。

> 唯一允许进 BPMN 的自定义类是 `wfAssigneeResolver`（`AssigneeResolverListener`），`BpmnXmlValidator` 白名单之外的一律拒绝部署。

## 十三、规则引擎 `/api/rules`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/rules/fields` | `rule:view` | 可用字段目录（来自各模块清单的 `rule-fields`） |
| GET | `/api/rules/{scope}` | `rule:view` | 取某作用域的规则集（最新版本） |
| PUT | `/api/rules/{scope}` | `rule:edit` | 保存新版本 |
| POST | `/api/rules/{scope}/evaluate` | `rule:view` | 试算；`dryRun` 与正式执行共用同一实现 |

规则只有「字段 + 运算符 + 值」三元组，**不接受表达式文本**。

## 十四、低代码元数据 `/api/metadata`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/metadata/{kind}` | `meta:view` | `kind` 取 `FORM`/`LIST`/`FLOW`；列出所有 Schema |
| GET | `/api/metadata/{kind}/{code}` | `meta:view` | 取最新版本 |
| POST | `/api/metadata/{kind}/{code}` | `meta:edit` | 保存新草稿版本；`schema` / `uiSchema` 传 JSON 对象或 JSON 字符串都行（服务端统一序列化） |
| POST | `/api/metadata/{kind}/{code}/publish` | `meta:publish` | 发布（`PUBLISHED`） |
| POST | `/api/metadata/{kind}/{code}/suspend` | `meta:publish` | 作废整个定义（按 code 全版本）；只挡新绑定，已绑定节点仍按原样渲染 |
| POST | `/api/metadata/{kind}/{code}/activate` | `meta:publish` | 恢复启用 |
| GET | `/api/metadata/{kind}/{code}/render` | `meta:view` | 取渲染所需的 `schema + uiSchema` |

Schema 有字段白名单，禁 `script` / `expression` 类键。

---

## 十五、智能体 `/api/agent`

### 对话与恢复

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/agent/chat/stream` | `agent:chat` | SSE 对话；body `{agentCode?, sessionId?, pageKey, message}`，`pageKey` 必填 |
| GET | `/api/agent/chat/bootstrap` | `agent:chat` | 当前页可用 Agent（`agentId/agentCode/agentName`）；没有返回 `enabled=false` |
| POST | `/api/agent/hitl/{id}/decision` | `agent:chat` | HITL 决策（同意/拒绝），返回 SSE 继续同一会话 |
| GET | `/api/agent/hitl/pending` | `agent:chat` | 会话内待审批项，刷新后恢复审批卡 |
| POST | `/api/agent/external-calls/{callId}/result` | `agent:chat` | 外部工具执行结果回传，返回 SSE 恢复模型 |

`sessionId` 必须属于当前 `agentCode` 对应的 Agent：不一致时后端新建会话（`session` 事件返回新 id），
不复用其他 Agent 的上下文与工具配置。前端用 `bootstrap` 返回的 `agentId` 判断历史会话归属，
切换到其他 Agent 的会话时提示新建对话。

### 会话

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/agent/sessions` | `agent:session:view` | 我的会话；`all=true` 仅管理员可看全部 |
| GET | `/api/agent/sessions/{id}/messages` | `agent:session:view` | 历史消息与 blocks |
| DELETE | `/api/agent/sessions/{id}` | `agent:session:view` | 逻辑删除自己的会话 |
| GET | `/api/agent/sessions/{id}/context-usage` | `agent:chat` | 当前模型上下文估算（`estimatedTokens/contextWindow/percent/messageCount`），分母取 Agent 的 `contextWindow` |
| POST | `/api/agent/sessions/{id}/compact` | `agent:chat` | 手动压缩：调用 AgentScope 官方 `ConversationCompactor`（工具结果裁剪 + 官方摘要 prompt），摘要写回 agentscope `AgentState`，`keepMessages` 默认 20；返回压缩前后 token/消息数。自动压缩由官方 `CompactionMiddleware` 在每轮推理前执行 |

### 观测

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/agent/observe/sessions` | `agent:metrics:view` | 全租户会话列表；`agentId/userId/keyword/page/size` |
| GET | `/api/agent/observe/sessions/{id}` | `agent:metrics:view` | 会话详情：`session/systemPrompt/modelCallCount/runs/mergedRequest` |
| GET | `/api/agent/observe/agents` | `agent:metrics:view` | 筛选下拉的精简 Agent 选项 |
| GET | `/api/agent/observe/export` | `agent:metrics:view` | 批量导出 JSON（流式）；`scope=all/filtered`、`limit<=500`、`pretty` |
| GET | `/api/agent/observe/sessions/{id}/export` | `agent:metrics:view` | 单会话导出 |

`mergedRequest` 只取消息最多的一次调用（请求体是累积的，逐次返回会 MB 级重复），
末尾补最后一条助手回复；详情不返回每次调用的原始 `request`，调用次数用 `modelCallCount`。
批量导出按会话流式写出（`Transfer-Encoding: chunked`），默认 gzip 压缩；
导出路径已在 `CachedResponseFilter` 中跳过，不能被响应缓存包装。

### 配置

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/agent/definitions` | `agent:config:view` | 定义列表 |
| POST | `/api/agent/definitions` | `agent:config:edit` | 新建 |
| PUT | `/api/agent/definitions/{id}` | `agent:config:edit` | 修改；revision 变化会让运行时缓存失效 |
| DELETE | `/api/agent/definitions/{id}` | `agent:config:edit` | 删除；同一事务内先清理该 Agent 的会话、HITL、外部调用，再删定义（工具/技能绑定由外键级联） |
| GET | `/api/agent/definitions/{id}/tools` | `agent:config:view` | 工具目录 + 当前策略 |
| PUT | `/api/agent/definitions/{id}/tools` | `agent:config:edit` | 保存工具启停/审批策略 |
| GET | `/api/agent/definitions/{id}/skills` | `agent:config:view` | 技能索引 + 绑定状态 |
| PUT | `/api/agent/definitions/{id}/skills` | `agent:config:edit` | 保存技能绑定 |
| POST | `/api/agent/definitions/{id}/test` | `agent:config:edit` | 真实调用一次模型，返回耗时 |

### 技能与工具目录

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/agent/skills` | `agent:config:view` | 技能列表 |
| POST | `/api/agent/skills/rescan` | `agent:config:edit` | 重新扫描 skills 目录 |
| GET | `/api/agent/skills/{skillName}` | `agent:config:view` | 技能正文 |
| POST | `/api/agent/skills` | `agent:config:edit` | 在线新建 |
| PUT | `/api/agent/skills/{skillName}` | `agent:config:edit` | 在线编辑 |
| DELETE | `/api/agent/skills/{skillName}` | `agent:config:edit` | 删除（含索引） |
| POST | `/api/agent/skills/upload` | `agent:config:edit` | zip 导入（可含多个技能目录） |
| POST | `/api/agent/skills/upload-folder` | `agent:config:edit` | 文件夹导入 |
| GET | `/api/agent/tools/directory` | `agent:config:view` | 工具目录；支持 `group` / `keyword` / `page` / `size`，返回 `{items,total,groups}`（不带筛选参数时为旧数组） |
| POST | `/api/agent/tools/directory` | `agent:config:edit` | 新增自定义工具 |
| PUT | `/api/agent/tools/directory/{toolCode}` | `agent:config:edit` | 修改 |
| DELETE | `/api/agent/tools/directory/{toolCode}` | `agent:config:edit` | 删除 |

工具表 `agent_tool_definition` 有 `group_name` 分组字段；从注册端点创建的工具 `config` 带
`{serviceCode,endpointCode,source:"ENDPOINT"}`，只读端点由服务端网关注入凭证执行，有副作用端点由前端经 BFF 代理执行。
平台注册端点**不会自动投影成工具**，需要在「新建外部工具」里点「从注册端点选用」显式创建。

### 外部服务目录 `/api/integration/admin`

平台注册的服务与端点已落库（`platform_external_service` / `platform_external_endpoint`），
端点是「可选用目录」，凭证只进不出（页面不回显，编辑时留空保持旧值）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/integration/admin/services` | `integration:service:view` | 服务 + 端点列表（含 `credentialConfigured`） |
| POST | `/api/integration/admin/services` | `integration:service:edit` | 新建服务 |
| PUT | `/api/integration/admin/services/{code}` | `integration:service:edit` | 编辑服务（凭证/静态头留空保持旧值） |
| DELETE | `/api/integration/admin/services/{code}` | `integration:service:edit` | 删除服务 |
| POST | `/api/integration/admin/services/{code}/endpoints` | `integration:service:edit` | 新建端点 |
| PUT | `/api/integration/admin/services/{code}/endpoints/{endpointCode}` | `integration:service:edit` | 编辑端点 |
| DELETE | `/api/integration/admin/services/{code}/endpoints/{endpointCode}` | `integration:service:edit` | 删除端点 |
| GET | `/api/integration/proxy/services` | — | 端点投影清单，供「从注册端点选用」回填表单 |

写操作后立即刷新注册表，网关调用随即生效。`status` 取 `ACTIVE` / `DISABLED`；`authType` 取 `NONE` / `API_KEY` / `BEARER`。

### SSE 事件

| 事件 | 说明 |
|---|---|
| `session` | 会话信息（sessionId / agentCode / agentName） |
| `text` / `thinking` | 正文 / 思考增量 |
| `tool_call` / `tool_result` | 工具调用与结果；`phase=start/delta/end` |
| `skill` | 加载技能 |
| `hitl` / `hitl_resolved` | 需确认 / 决策结果 |
| `external_tool_call` | 外部工具参数下发 |
| `external_wait` / `external_result` | 多调用等待 / 单个结果回传 |
| `ui` | 前端渲染指令：`kind=task_list` / `workflow_preview` 等 |
| `error` / `done` | 错误与收尾；`done.paused=true` 表示停在 HITL 或外部调用 |

工具按 `pageKey` + 用户权限点 + Agent 绑定过滤；外部工具结果里的 `ui` 只下发前端，不进入模型上下文。

## 十六、运维端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/actuator/health` | 健康检查（含 DB） |
| GET | `/actuator/ports` | **16 个端口各自绑到了哪个实现**，排查降级时第一个看这个 |
| GET | `/actuator/info` | 应用信息 |

---

## 十七、开放平台调用方式（给第三方看）

这一节可以直接复制给接入方。

### 请求头

| 头 | 必需 | 说明 |
|---|---|---|
| `X-App-Key` | ✅ | 应用标识 |
| `X-Timestamp` | ✅ | 秒级时间戳，与服务端相差 ≤ 5 分钟 |
| `X-Nonce` | ✅ | 随机串，10 分钟内不可重复 |
| `X-Signature` | ✅ | 见下 |
| `Authorization` | 视 `auth_mode` | `Bearer <access_token>` |
| `X-Sandbox` | 可选 | `true` 走沙盒租户，不写生产数据 |
| `Idempotency-Key` | 可选 | 写接口幂等 |

### 待签串

六个部分用 `\n` 连接（查询串为空也要占一行）：

```
GET\n/openapi/v1/ping\n\n1730000000\n6f1c9a2d4b7e45c8a0d3f6e9b2c5d8a1\ne3b0c442...b855
```

签名 = `base64(hmac_sha256(SigningSecret, 待签串))`。

### 换令牌

```bash
curl -X POST http://host/openapi/v1/oauth/token \
  -H 'Content-Type: application/json' \
  -d '{"appKey":"ak_xxx","clientSecret":"cs_xxx","grantType":"client_credentials"}'
```

用户身份走授权码：浏览器跳 `/#/oauth/authorize?client_id=...&redirect_uri=...&scope=...&state=...`，
用户同意后拿 `code`，再用 `grantType=authorization_code` + `code` + `redirectUri` 换带 `uid` 的令牌。

### 开放平台错误码

| code | HTTP | 含义 |
|---|---|---|
| `SIGNATURE_HEADERS_MISSING` | 401 | 缺签名头 |
| `APP_KEY_MISSING` / `APP_NOT_FOUND` | 401 | AppKey 缺失或不存在 |
| `APP_DISABLED` | 403 | 应用已停用 |
| `IP_NOT_ALLOWED` | 403 | 来源 IP 不在白名单 |
| `TIMESTAMP_INVALID` / `TIMESTAMP_EXPIRED` | 401 | 时间戳格式错或超窗口 |
| `SIGNATURE_INVALID` | 401 | 待签串或密钥不对 |
| `NONCE_REPLAYED` | 409 | nonce 重复 |
| `TOKEN_REQUIRED` | 401 | 该模式要求令牌 |
| `TOKEN_INVALID` | 401 | 令牌签名不对或过期 |
| `TOKEN_BINDING_MISMATCH` | 401 | 令牌不属于该 AppKey |
| `SUBJECT_REQUIRED` | 403 | 该接口必须代表真实用户 |
| `USER_NOT_FOUND` | 401 | 授权用户不存在或已停用 |
| `USER_PERMISSION_DENIED` | 403 | 被代理用户没有该业务权限 |
| `TENANT_MISMATCH` | 403 | 用户与应用不同租户 |
| `SCOPE_DENIED` | 403 | 令牌缺所需 scope |
| `QPS_LIMITED` / `DAILY_QUOTA_EXCEEDED` | 429 | 限流 / 配额用尽 |
| `API_NOT_FOUND` | 404 | 路径不在开放目录中 |
| `API_NOT_GRANTED` | 403 | 应用未被授权该接口 |
| `SANDBOX_DISABLED` / `SANDBOX_NOT_SUPPORTED` | 403 | 沙盒不可用 |
