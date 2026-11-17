# 04 · REST 接口清单

## 本文档解决什么

「这个能力对外暴露了哪些接口、要什么权限」。共 **76 个方法级映射**，按模块分组。
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

---

## 十二、工作流 `/api/workflow`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/workflow/definitions` | `wf:deploy` | 部署 BPMN 2.0 XML；`strategy=KEEP_OLD/MIGRATE_ALL` 决定老实例是否迁移到新版本 |
| GET | `/api/workflow/definitions` | `wf:view` | **全部版本**（不是只给 latest），带每版运行中/已结束实例数与挂起态 |
| GET | `/api/workflow/overview` | `wf:view` | 概览数字：定义数、运行中/已结束实例数、已挂起定义数；首页用这个，不要为拿数字拉全量明细 |
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
| GET | `/api/workflow/instances/{processInstanceId}/tasks` | `wf:task` | 旧接口：只返回**运行中**任务，已结束实例为空；详情请用 `/detail` |
| GET | `/api/workflow/tasks` | `wf:task` | 按 `assignee` 查任务（candidateOrAssigned，含加签人） |
| GET | `/api/workflow/tasks/todo` | `wf:task` | 当前登录人的待办 |
| POST | `/api/workflow/tasks/{taskId}/complete` | `wf:task` | 办理；`decision=APPROVE/REJECT`，驳回原因写进流水并计入打回次数 |
| POST | `/api/workflow/tasks/{taskId}/add-sign` | `wf:task` | 加签：改成候选任务，被加签人能在待办里看到 |
| POST | `/api/workflow/tasks/{taskId}/transfer` | `wf:task` | 转办 |
| POST | `/api/workflow/tasks/{taskId}/rollback` | `wf:rollback` | 回退到指定节点；校验节点属于该定义，原因写进流水 |
| GET | `/api/workflow/node-configs` | `wf:view` | 节点结构化配置；`processKey` 必填，`version=0` 表示取最新已部署版本 |
| PUT | `/api/workflow/node-configs` | `wf:config` | 保存节点配置草稿（找人/签署/表单/决策/触发/条件），部署时由服务端编译进 BPMN |
| GET | `/api/workflow/node-configs/options` | `wf:view` | 属性面板下拉数据：找人类型、签署类型、角色、组织、已发布表单、运算符、触发动作 |
| GET | `/api/workflow/instances/{processInstanceId}/form-data` | `wf:view` | 该实例各节点提交的表单数据（含租户校验） |
| GET | `/api/workflow/widgets` | `wf:view` | 组件素材库（可复用表单片段） |
| POST | `/api/workflow/widgets` | `wf:form` | 保存自定义组件素材，供设计器插入或别的页面单独渲染 |
| POST | `/api/workflow/lab/init` | `wf:lab` | 幂等初始化演练环境：组织/角色/6 个测试账号/表单/演示流程/节点配置 |
| GET | `/api/workflow/lab/status` | `wf:lab` | 演练环境状态、账号清单、演示流程版本与隔离验证租户 |

### 结构化节点配置怎么变成 BPMN

页面上只提供**结构化字段**，不让人写表达式或脚本，服务端在部署时编译：

| 页面配置 | 编译结果（`NodeConfigCompiler`） |
|---|---|
| 找人逻辑 `DEPT_MANAGER` / `ROLE` / `USER` / `ORG_ROLE` / `PREV_OPERATOR` / `FORM_FIELD` | `AssigneeResolver` 在发起和每次办理前算出成员，写进变量 `wf_assignee_<节点>`（单签）或 `wf_assignees_<节点>`（多实例） |
| 签署方式 `SINGLE` / `OR_SIGN` / `COUNTERSIGN` / `SEQUENTIAL` | `SINGLE` → `flowable:assignee`；`OR_SIGN` → 候选组；`COUNTERSIGN` / `SEQUENTIAL` → `multiInstanceLoopCharacteristics`，通过比例写成 `completionCondition`（默认 100%） |
| 绑定表单 | `flowable:formKey`，办理时 `formData` 落 `wf_form_data` |
| 决策按钮（同意/驳回 + 是否必填原因 + 最少字数） | `wf_node_config.decision_config`，办理时校验，不通过就 400 |
| 出线条件（字段 + 运算符 + 值） | `sequenceFlow` 上的 `${field op value}`，字段名走 `[A-Za-z_][A-Za-z0-9_]{0,63}` 白名单，字符串值禁 `'` `$` `}` |

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
| GET | `/api/metadata/{kind}/{code}/render` | `meta:view` | 取渲染所需的 `schema + uiSchema` |

Schema 有字段白名单，禁 `script` / `expression` 类键。

---

## 十五、运维端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/actuator/health` | 健康检查（含 DB） |
| GET | `/actuator/ports` | **16 个端口各自绑到了哪个实现**，排查降级时第一个看这个 |
| GET | `/actuator/info` | 应用信息 |

---

## 十六、开放平台调用方式（给第三方看）

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
