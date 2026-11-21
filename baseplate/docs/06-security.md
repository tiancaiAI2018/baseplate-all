# 06 · 安全模型

## 本文档解决什么

「谁能调什么、凭什么身份、数据能看多少」。这套东西里安全相关的改动最容易出事，改之前先读完这篇。

---

## 一、两套令牌，两个签名域

| | 内部登录 JWT | 开放平台 access_token |
|---|---|---|
| 签发方 | `JwtIssuer`（identity） | `OpenTokenService`（platform） |
| 密钥 | `BASEPLATE_JWT_SECRET` | `BASEPLATE_OPEN_TOKEN_SECRET` |
| claims | `jti/sub/tenant/username/roles/permissions/mcp/iat/exp` | `jti/sub=appKey/tenant/scope/uid?/uname?/typ=openapi/iat/exp` |
| 校验方 | `JwtAuthenticationFilter` | `OpenApiAuthFilter` |
| 适用路径 | `/api/**` | `/openapi/**` |

**这两把密钥必须不同。** 历史上它们共用了 `BASEPLATE_JWT_SECRET`，结果第三方拿自己的 access_token
打 `/api/identity/users`，内部过滤器验签通过，只是 `username` 为空——等于越权进了内部接口。
现在 `PlatformAutoConfiguration.openTokenSecretGuard` 在启动期比对，同值直接抛 `IllegalStateException`。

配套的另一个细节：`JwtAuthenticationFilter.shouldNotFilter` 必须放行 `/openapi/**`，
否则第三方带着自己的 Bearer 令牌调开放接口，会先被内部过滤器当成非法内部令牌 401 掉。

---

## 二、登录

### 流程

```
POST /api/auth/login {username, password, tenant}
  ├─ 账号不存在 / 密码错       → 统一 "用户名或密码错误"（不区分，防枚举）
  ├─ 已锁定                    → "账号已锁定，请稍后再试"
  ├─ 状态非 ACTIVE             → "账号不可用"
  ├─ 密码校验通过               ├─ 失败次数清零
  │                            ├─ 签发 JWT（含 roles + permissions 快照）
  │                            └─ 写 sys_login_log
  └─ 返回 { accessToken, expiresIn, mustChangePassword }
```

### 失败锁定

连续失败 `max-failed-attempts`（默认 5）次，锁 `lock-minutes`（默认 15）分钟。计数在 `sys_user` 上，
不依赖 Redis（避免 Redis 挂了就能无限爆破）。

### 首登强制改密

以下情况 `must_change_password = true`：

- 管理员创建用户时设了初始密码
- 管理员重置了密码
- 密码超过 `password-expire-days` 且 `password-expire-enabled=true`

此时除 `/api/auth/password`、`/api/auth/logout`、`/api/auth/me` 外，所有接口返回
`403 PASSWORD_CHANGE_REQUIRED`，前端自动跳 `/change-password`。

### 改密后的令牌处理

`PasswordService.applyNewPassword` 会调 `TokenBlacklist.revokeAllForUser(userId)`——
该用户所有已签发令牌立即失效，不等自然过期。所以改完密码需要重新登录。

### 密码策略

`PasswordPolicy.validate(rawPassword, username)`：

- 长度 ≥ `password-min-length`（默认 8）
- 必须含大写、小写、数字
- 不能包含用户名（不区分大小写）
- 不能与最近 `password-history-size`（默认 5）次重复

**任何入口改密都必须走 `PasswordService`**（自己改、管理员重置），它内部统一做策略 + 历史 + 吊销三件事。
新增第二条改密路径 = 绕过策略。

---

## 三、权限模型

```
用户 ──< sys_user_role >── 角色 ──< sys_role_permission >── 权限编码
                                                              │
                                    模块清单声明 ──────────────┘
                                    (sys_permission 落库，label 可人工改写)
```

- 权限编码格式：`模块:动作`，如 `user:view`、`customer:create`。
- 权限点来自**模块清单**，启动时同步进 `sys_permission`；控制台可新增自定义、改名、停用，但**声明来源的不能删**。
- 角色分配是**覆盖式**：`PUT /roles/{id}/permissions` 传完整列表。
- 高危操作有独立权限点，不要和普通办理权混在一起：工作流 `wf:manage`（作废 / 恢复 / 版本迁移 / 终止实例）、`openapi:rotate`（密钥轮换）。`wf:task` 只能发起和办理，不能改流程定义。

### 校验

```java
@RequiresPermission("customer:create")
```

`PermissionAspect` 的行为：

1. 从 `CurrentUserProvider.current()` 拿 `permissions`
2. **`SUPER_ADMIN` 角色短路放行**（不看具体权限点）
3. 否则要求 `permissions` 包含注解里的**任意一个**值

> 踩过的坑：清单里的编码与注解里的字符串必须完全一致。拼错不会报错，只会永远 403 或永远放行（取决于方向）。

### 新增权限点的两条路

| 方式 | 适用 | 是否重启 |
|---|---|---|
| 模块清单 `permissions`（推荐） | 代码里已经用了这个编码 | 需要，同步后进目录 |
| 控制台「权限点管理」新建 | 补一个代码里还没用、但将来要用的 | 不需要 |

---

## 四、数据权限

**显式下传，不做隐式织入。** 这是刻意选择：MyBatis-Plus 的 `DataPermissionInterceptor` 会在 SQL 层
偷偷加条件，出问题极难排查。

```java
DataScope scope = dataScopeProvider.forCurrentUser("User");
// scope 里给出可见的组织 id 集合 / 是否全量
```

然后在仓储里显式拼条件。`IdentityDao.searchUsers` 就是这么做的：

```java
if (!dataScope.allVisible()) {
    sql.append(" AND org_id = ANY(?)");
    args.add(dataScope.orgIds());
}
```

`DefaultDataScopeProvider` 按角色上的 `data_scope` 字段算范围。
模块清单里的 `data-scopes: [{ aggregate: User, dimension: org_id }]` 声明该实体按什么维度隔离。

**租户隔离不靠数据权限**，靠每条 SQL 都带 `tenant_id`。两者是两层：
租户是硬隔离（不可能跨），数据范围是软隔离（同租户内按组织/本人）。

---

## 五、开放平台

### 5.1 请求校验链路

```
/openapi/**
  ▼
验签           X-App-Key + X-Timestamp + X-Nonce + X-Signature
  │            待签串 = method\npath\nquery\nts\nnonce\nsha256(body)
  │            验签失败 → 401，且不消费 nonce（防刷 Redis）
  ▼
时间窗         |now - ts| ≤ timestamp-window（默认 5 分钟）
  ▼
nonce          SET NX EX 10min，key 含 tenant + appKey；重复 → 409
  ▼
目录匹配       按 method + path 在 open_api 里找；找不到 → 404 API_NOT_FOUND
  ▼
应用授权       该 app 是否被授权这个 code；否 → 403 API_NOT_GRANTED
  ▼
令牌           纯签名模式可无；USER 主体或非 APPKEY_HMAC 模式必须有
  ▼
主体判定       ★ 见 5.3
  ▼
scope          目录声明的 requiredScope，或「非 GET 必须 write」兜底
  ▼
用户权限       ★ 见 5.4
  ▼
配额          QPS + 日配额（Redis）
  ▼
沙盒          X-Sandbox: true 时切到 sandbox: 前缀租户
  ▼
下传用户上下文 → 业务 Controller
```

**顺序不能随意改**：目录匹配必须在令牌之前（不知道要求什么就判不了满足不满足）；
配额放在授权之后（非法请求不该消耗正常配额）。

### 5.2 双凭证

| 模式 | 含义 | 必须带令牌 |
|---|---|---|
| `APPKEY_HMAC` | 只用签名证明应用身份 | 否 |
| `OAUTH2_HMAC` | 令牌 + 签名，双凭证 | 是 |
| `OAUTH2_ONLY` | 只带令牌，不签名 | 是 |

与之独立的是 `grant_types`（怎么拿令牌）：`client_credentials` / `authorization_code` / `refresh_token`，可多选。
**这两个维度不要混**——一个是「请求要带什么」，一个是「能怎么换令牌」。

### 5.3 主体类型（最关键的设计）

```java
@OpenApi(..., subject = OpenApiSubject.USER)
```

| 注解 subject | 请求凭证 | 结果 |
|---|---|---|
| `CLIENT` | 纯签名 / 机器令牌 | 通过，按应用身份记审计 |
| `CLIENT` | 用户令牌 | 通过，但忽略用户身份 |
| `USER` | 纯签名 / 机器令牌 | `403 SUBJECT_REQUIRED` |
| `USER` | 用户令牌，缺该用户权限 | `403 USER_PERMISSION_DENIED` |
| `USER` | 用户令牌 + 权限够 | 通过，用户上下文下传 |
| `ANY` | 任意 | 带用户身份按用户处理，否则按应用 |

为什么必须这样：`client_credentials` 换来的令牌只代表应用，代表不了任何自然人。
一个「代客下单」的接口如果被机器令牌调通，业务代码里 `currentUser.current()` 拿到的就是空值——
要么 NPE，要么把匿名请求当合法调用。让它静默降级成匿名是最坏的选择。

### 5.4 用户上下文怎么下传

`OpenApiAuthFilter` 在放行前：

```java
UserDirectory.Snapshot snapshot = userDirectory.load(claims.userId())   // 不存在/停用 → 401
if (!app.tenantId().equals(snapshot.tenantId())) → 403 TENANT_MISMATCH  // 跨租户直接拒
if (permission != null && !snapshot.permissions().contains(permission)
        && !snapshot.roles().contains("SUPER_ADMIN")) → 403 USER_PERMISSION_DENIED

CurrentUserProviderImpl.set(new CurrentUser(...));
chain.doFilter(...);
// finally: CurrentUserProviderImpl.clear();
```

于是下游业务代码**一行都不用改**：`@RequiresPermission` 自动生效、数据权限自动生效、
`currentUser.current()` 拿到的就是被代理的人。

注意 `UserDirectory` 是**可选注入**的：只装 platform 不装 identity 时它是 null，
此时 USER 类型接口返回 `503 USER_DIRECTORY_UNAVAILABLE`——**明确拒绝，而不是放行**。

### 5.5 授权码流程

```
浏览器 ──302──▶ /#/oauth/authorize?client_id&redirect_uri&scope&state
                     │ 未登录 → 跳登录页，登录后 ?redirect= 回跳
                     ▼
                GET /api/platform/oauth/authorize/context   展示应用名、scope 中文说明、是否授权过
                     ▼
                用户点「同意」
                     ▼
                POST /api/platform/oauth/authorize           校验 redirect_uri ∈ 白名单（精确匹配）
                     │                                       生成 code（默认 5 分钟）
                     ▼
浏览器 ──302──▶ redirect_uri?code=...&state=...
                     ▼
第三方后端 ──▶ POST /openapi/v1/oauth/token
              grant_type=authorization_code + code + redirect_uri + client 凭证
                     ▼
              带 uid 的 access_token（默认 2h）+ refresh_token（默认 30d）
```

四条安全约束：

1. **必须两步**（先看后同意）。做成一个 GET 直接发码，浏览器预取、聊天软件预览链接都会替用户「点同意」。
2. **`redirect_uri` 精确匹配、先校验再重定向**，不支持通配。否则就是开放重定向，攻击者能拿到别人的授权码。
3. **code 原子消费**：`UPDATE ... WHERE consumed_at IS NULL AND expires_at > now() AND redirect_uri = ? RETURNING *`。
   先查后改会留并发窗口，两个请求能同时换到令牌。
4. **refresh 轮换**：换新时旧令牌立刻 `revoked_at`。一张泄漏的 refresh token 只能用一次，
   被第三方先用掉的话，真正接入方下次续期会失败，异常自己暴露。

`refresh_token` **只存 SHA-256**，库被拖走也换不出新令牌。

### 5.6 沙盒

请求带 `X-Sandbox: true` 时，租户变成 `sandbox:<原租户>`，数据与生产隔离。
条件是应用 `sandbox_enabled=true` **且**平台 `sandbox-enabled=true` **且**该接口 `@OpenApi(sandbox=true)`。

### 5.7 密钥存储

- `client_secret` 与 `signing_secret` 在库里是 **AES-GCM 密文**，密钥来自 `BASEPLATE_SECRET_KEY`。
- 明文只在创建与轮换时返回一次。
- 轮换会同时撤销该应用全部刷新令牌（否则旧授权还能续出新令牌，轮换形同虚设）。

---

## 六、智能体（AI）安全

### 6.1 工具不是新的权限通道

| 工具类型 | 安全边界 |
|---|---|
| 内部工具 | 服务端执行，`requirePermission` 按当前用户权限点校验；只做只读查询 |
| 外部工具 | `direct` 模式直接下发 schema；收敛模式由入口工具发现后抛 `ToolSuspendException` 转前端执行。前端始终带用户令牌调现有 REST，`@RequiresPermission`、`@AuditLog`、`requireOwnedTask` 全部照常生效 |

工具在**进入模型之前**就已经过滤：`selectTools` 依次检查页面匹配、用户权限点、Agent 绑定与审批策略。
模型看不到一个它调不动的工具。

### 6.2 页面作用域

Agent、工具、会话三者都带 `pageKey`。换页继续旧会话时同步 `agent_session.page_key`，
否则恢复流会用旧页面重建 Agent，出现「工具调用成功、恢复时找不到」。

### 6.3 HITL 与外部调用

- 审批策略为 `ASK` 的工具先落 `agent_hitl_request`，用户同意后带 `ConfirmResult` 恢复同一会话；收敛模式下由入口工具的 `checkPermissions` 代报审批，恢复时带放行标记重新执行
- 外部调用落 `agent_external_call`，结果只能由**发起人本人**回传（`findPending(tenant, userId, callId)`）
- 多个调用按 `replyId` 收齐后才恢复模型；过期时间与 HITL 一致

### 6.4 密钥与上下文

- 模型 API Key 只存库/环境变量，`/api/agent/definitions` 不回显
- 外部工具结果里的 `ui`（可能含整段 BPMN XML）不进模型上下文，只走 `ui` 事件
- 前端 markdown 渲染统一过 DOMPurify；工具参数展示做长度截断

---

## 七、红线清单

改安全相关代码前逐条对照：

1. [ ] 没有把 `BASEPLATE_JWT_SECRET` 与 `BASEPLATE_OPEN_TOKEN_SECRET` 设成同一个值
2. [ ] 没有让 `JwtAuthenticationFilter` 处理 `/openapi/**`
3. [ ] 没有把 `subject = USER` 的接口降级成允许机器令牌
4. [ ] 用户权限校验用的是 `UserDirectory`（同一份数据），没有在平台侧另建一套
5. [ ] 没有去掉 `TENANT_MISMATCH` 检查
6. [ ] `redirect_uri` 仍是精确匹配，没有改成前缀或通配
7. [ ] 授权码仍是原子消费，没有改成「先查再改」
8. [ ] refresh token 仍是哈希存储 + 轮换
9. [ ] 新增的 `/api` 控制器没有漏 `@RequiresPermission`（`SUPER_ADMIN` 会短路，测试时用普通账号验证）
10. [ ] 租户 id 从 `CurrentUser` 取，不从请求参数取
11. [ ] 智能体有副作用的工具做成了外部工具，没有在内部工具里直接改业务数据
12. [ ] 新工具声明了正确的 `pages` 和 `permissionCode`，并确认普通账号在目标页面能看到

---

## 八、已知未做

诚实列出来，别当成已有能力：

| 项 | 影响 | 补法 |
|---|---|---|
| 无 PKCE | 不支持纯前端应用（拿不到 client_secret 的那种） | 授权端点加 `code_challenge`，换码时校验 `code_verifier` |
| 无 refresh 令牌「家族级联撤销」 | 旧令牌被重放时只失效单张，不会撤销整条授权链 | 给 refresh 记录加 `family_id`，检测到已用令牌被重放就撤销全家族 |
| 无登录验证码 / 二次验证 | 暴力破解只能靠锁定策略挡 | 接入 MFA 或图形验证码 |
| 无接口级 IP 白名单（内部接口） | 内网接口只能靠网络层隔离 | 网关层做，或加拦截器 |
| 审计日志无防篡改 | 有库权限的人能删审计 | 落地到只追加存储 / 外部日志系统 |
