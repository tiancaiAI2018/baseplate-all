# 08 · 开发规范

## 本文档解决什么

「这么写对不对」。规范的目的不是好看，是避免几类特定的事故：越权、串租户、静默 500、排查不了。

---

## 一、包结构

每个能力/业务模块内部统一四层：

```
com.example.baseplate.<模块>.
├── domain/                 领域对象与不变式（不依赖框架）
├── application/            用例编排、事务边界
├── adapter/
│   ├── in/web/             控制器、过滤器、拦截器
│   └── out/persistence/    DAO / Repository（显式 SQL）
├── config/                 @ConfigurationProperties
└── autoconfigure/          自动配置（条件装配）
```

类名后缀约定：

| 后缀 | 含义 | 例子 |
|---|---|---|
| `*Controller` | HTTP 入口，只做参数绑定与转发 | `UserAdminController` |
| `*Service` | 用例，事务边界，业务规则 | `PasswordService` |
| `*Dao` / `*Repository` | 数据访问，显式 SQL | `IdentityDao` |
| `*Filter` / `*Interceptor` | 请求链上的横切 | `JwtAuthenticationFilter` |
| `*Aspect` | 注解织入 | `PermissionAspect` |
| `*Properties` | 配置绑定 | `IdentityProperties` |
| `*Row` / `*View` / `*Command` | record 型数据载体 | `UserRow`、`AppView`、`AppCommand` |

**不要**造 `XxxManager`、`XxxHelper`、`XxxUtil` 这种语义模糊的类。工具类要么进 `domain` 当值对象，要么是私有方法。

---

## 二、三层各能做什么

| 层 | 能做 | 不能做 |
|---|---|---|
| Controller | 参数绑定、`@RequiresPermission`、`@RateLimit`、调 Service、组装返回体 | 业务判断、直接操作 DB、写事务 |
| Service | 业务规则、事务、调多个 Repository、发事件、`@AuditLog` | 碰 `HttpServletRequest`、拼 SQL、返回 `ResponseEntity` |
| Repository | 显式 SQL、行映射 | 业务规则、调用其他 Service、返回 Controller 用的 View 对象 |

一条经验：**如果 Service 方法里出现了 `request.getHeader(...)`，说明分层错了**。

---

## 三、SQL 规范

### 3.1 每张业务表都有 `tenant_id`，每个查询都带它

```sql
-- 对
SELECT * FROM crm_customer WHERE tenant_id = ? AND id = ?

-- 错（跨租户泄露）
SELECT * FROM crm_customer WHERE id = ?
```

租户 id **从 `CurrentUser` 取**，不从请求参数取：

```java
CurrentUser user = currentUser.current();
repository.findById(user.tenantId(), id);
```

### 3.2 参数顺序逐位对齐

```java
// 签名：findById(String tenantId, long id)
// SQL：  WHERE tenant_id = ? AND id = ?
// 调用：
repository.findById(user.tenantId(), id);   // ✅
```

**历史上这里写反过一次**（`findById(villageId, postId)` 拼成 `WHERE id=? AND village_id=?`），
同一个错误在 3 个方法里复现，因为参数都是 long/String 混着，编译器不报错，只在特定数据下暴露。
写 `findById` 这类双参数方法时，把 SQL 与实参**逐位对齐念一遍**。

### 3.3 列名显式，不用 `SELECT *`

`SELECT *` 在加列后会带出不该给前端的字段（比如密码哈希）。

### 3.4 JSONB 比较用 `@>`，不用 `?|`

```sql
-- 对：category_ids 是 jsonb 数组
WHERE category_ids @> '[13]'::jsonb

-- 错：永远 0 命中
WHERE category_ids ?| array['13']
```

### 3.5 取 JSONB 内容给前端时转文本

```sql
-- 直接查 jsonb 列，JDBC 会返回 PGobject，序列化成 {"type":"jsonb","value":"..."}
SELECT detail::text AS detail FROM aud_access_log WHERE id = ?

-- 前端要的是 JSON 对象
```

### 3.6 时间列用 `TIMESTAMPTZ`

`TIMESTAMP` 没有时区，跨时区部署会集体偏移。

### 3.7 迁移脚本

- 文件名：`V<yyyyMMddHHmmss>__<snake_case_说明>.sql`，放在所属模块的 `src/main/resources/db/migration/<模块>/`
- **已经执行过的脚本一个字符都不能改**（Flyway 校验 checksum）
- 改逻辑就新建一个版本，用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 这类幂等写法
- 加列、加索引尽量与业务变更同一次提交，不要留「以后再补」

---

## 四、DTO 与序列化

### 4.1 原始类型一律用包装类型

Jackson 3 开启了 `FAIL_ON_NULL_FOR_PRIMITIVES`，请求体缺字段或显式传 `null` 时，
`boolean`/`int` 会**直接解析失败**，表现为 400「请求体解析失败」。

```java
// 错
public record AppRequest(String name, boolean sandbox, int qpsLimit) {}

// 对
public record AppRequest(String name, Boolean sandbox, Integer qpsLimit) {}
```

### 4.2 枚举用字符串传递

```java
public enum OpenApiSubject { CLIENT, USER, ANY }
// JSON 里就是 "CLIENT"，不要用 ordinal
```

### 4.3 只读视图不要暴露内部字段

`AppView` 不含 `clientSecretEnc`；`UserRow` 不出 Controller，要转成 View 再返回。

---

## 五、异常

| 场景 | 抛什么 | 变成 |
|---|---|---|
| 参数不合法、业务校验失败 | `IllegalArgumentException` | `400 BAD_REQUEST`，`detail` 原样给前端 |
| 未认证 / 令牌无效 | `AuthenticationException` | `401 AUTH_FAILED` |
| 已认证但无权限 | `ForbiddenException` | `403 FORBIDDEN` |
| 资源不存在 | `IllegalArgumentException("xx 不存在")` | `400`（想要 404 用 `BaseplateException` 的 `NOT_FOUND`） |
| 冲突 | `BaseplateException("CONFLICT", ...)` | `409` |
| 限流 | `RateLimitedException` | `429` |

**不要 `new RuntimeException("...")`**：它会落到 catch-all，变成 `500 服务内部错误`，
调用方看不到原因，排查时也只能靠 traceId。全部异常映射在 `ApiExceptionHandler`。

错误信息写给**调用方**看，不要写内部实现细节（表名、SQL、堆栈）。

---

## 六、事务

- 边界在 Service，不在 Controller 也不在 Repository。
- 只给写方法加 `@Transactional`；只读方法不加（省一个连接绑定）。
- 一个事务里不要做远程调用（HTTP、MQ 发送尽量后置）。
- 需要「写库 + 发事件」时，把事件发送放在事务提交后（`TransactionSynchronization`），或接受重复投递并做幂等。

```java
@Transactional
@AuditLog(action = "crm:create-customer", resource = "crm_customer")
public Map<String, Object> create(CreateCommand command) { ... }
```

---

## 七、日志

```java
private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

log.info("客户创建成功 id={} tenant={}", id, tenantId);
log.warn("开放接口 {} 已由控制台手工登记，现被注解声明覆盖", code);
log.error("未处理异常 traceId={}", traceId, e);
```

| 规则 | 原因 |
|---|---|
| 用占位符 `{}`，不要字符串拼接 | 级别关闭时不产生开销 |
| ERROR 必须带异常对象 | 否则丢堆栈 |
| 不打密码、令牌、密钥、完整手机号 | 日志会被采集到第三方系统 |
| 请求级日志由 `AccessLogFilter` 统一记，业务代码不要重复记 | 避免一条请求两条记录 |
| 关键标识（id、tenant、code）要带上 | 没有标识的日志等于没有 |

---

## 八、审计

| 类型 | 谁记 | 怎么开 |
|---|---|---|
| 访问审计 | `AccessLogFilter`，全部请求自动 | 无需操作 |
| 业务审计 | `AuditAspect` | 方法上加 `@AuditLog(action="x:y", resource="表名")` |
| 登录审计 | `AuthenticationService` 内部 | 无需操作 |

`@AuditLog` 加在 **Service 方法**上，不要加 Controller：

- 开放接口、内部接口、定时任务走同一个审计点
- Controller 层的参数脱敏由 `AuditAspect` 处理（密码、令牌类字段替换为 `***`）

---

## 九、幂等与限流

```java
@Idempotent(key = "crm:create-customer")            // 请求头 Idempotency-Key 换结果
@RateLimit(key = "crm:create-customer", limit = 20, windowSeconds = 60)
```

- 幂等 key 要**业务化**（能区分不同用户/不同资源），不要所有请求共用一个 key。
- 限流在**多实例**下必须用 Redis 实现；内存实现只对单实例准确。
- 写接口（POST/PUT/DELETE）默认都应该考虑加幂等。

---

## 十、模块清单

新增权限点时，`code` 用 `模块:动作` 格式：

```yaml
permissions:
  - { code: "customer:view", label: 查看客户, type: MENU }
```

- `type=MENU` 表示菜单级（决定菜单可见）
- `type=BUTTON` 表示按钮级
- `label` 是给运营看的中文名，**不要**把编码当 label

清单里声明的权限点，控制台**不能删除**（只能停用）。这是故意的：删了下一次启动会回来。

---

## 十一、代码风格

| 项 | 约定 |
|---|---|
| 缩进 | 4 空格（Java），2 空格（YAML/TS/Vue） |
| 行宽 | 尽量 ≤ 120 |
| 注释 | 写「为什么」，不写「是什么」。`// 用户不存在` 是废话，`// 停用用户直接返回空，开放令牌不该比登录态活得久` 才有价值 |
| 空 catch | 禁止。要么处理，要么注释说明为什么可以忽略（如审计写库失败不能影响响应） |
| `Optional` | 只在返回值用，不要做字段或参数 |
| record | 数据载体一律 record，不要手写 getter/setter |
| 常量 | `private static final`，不要散落魔法值 |

---

## 十二、禁止项清单

提交前对照，出现任何一条就重写：

1. [ ] `new RuntimeException(...)` 当业务异常用
2. [ ] SQL 里少了 `tenant_id`
3. [ ] `SELECT *` 查含敏感字段的表
4. [ ] DTO 用 `boolean` / `int` 原始类型
5. [ ] Controller 里写了业务判断或直接调 DAO
6. [ ] Service 里注入 `HttpServletRequest`
7. [ ] 业务模块 `import` 了另一个能力 starter 的 `Service`
8. [ ] `baseplate-api` 里 import 了 Spring / MyBatis / Jackson
9. [ ] 手改了已经执行过的 Flyway 脚本
10. [ ] 改了控制器路径/权限但没同步 `04-api.md`
11. [ ] 空 `catch` 块没有注释
12. [ ] 日志里打了密码、令牌、密钥
13. [ ] 前端页面里 `import axios` 绕过 `api/client.ts`
14. [ ] 前端手改了 `baseplate-bootstrap/src/main/resources/static/`
15. [ ] 新增 `@RequiresPermission` 但权限点没进模块清单（运行期永远 403）
16. [ ] 在内部工具里直接改业务数据（应声明为外部工具，由前端走 REST）
17. [ ] 在 system prompt 里拼「可用工具」列表（破坏前缀缓存）
18. [ ] 工具没有声明 `pages` / `permissionCode`，或工具编码与已有工具重复
19. [ ] `ui` 卡片用 `type` 表示子类型（会覆盖 SSE 事件名，必须用 `kind`）
