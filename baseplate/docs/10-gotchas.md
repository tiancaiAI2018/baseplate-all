# 10 · 踩过的坑

## 本文档解决什么

这些坑的共同点：**不报错、或者报的错指向完全无关的地方**。每条都花了时间才定位。
动手前扫一遍，能省掉重复踩。

格式：现象 → 根因 → 正确做法。

---

## 一、构建与启动

### 1. `spring-boot-starter-aop` 下载 404

Boot 4 把 AOP 的 starter 改名了。

```xml
<!-- 错 -->
<artifactId>spring-boot-starter-aop</artifactId>
<!-- 对 -->
<artifactId>spring-boot-starter-aspectj</artifactId>
```

### 2. Flyway 完全不执行，也没有任何报错

Boot 4 把 Flyway 拆成了独立模块，只引 `flyway-core` 不会激活自动配置。

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-flyway</artifactId>
</dependency>
```

而且 `spring.flyway.enabled` 默认是 `false`，要显式打开。

### 3. 某个模块的所有接口 404，但启动日志干净

条件装配写错了：

```java
// 错：@ConditionalOnBean 在自动配置排序里求值更早，此时 JdbcTemplate 还不存在
@ConditionalOnBean(JdbcTemplate.class)

// 对
@ConditionalOnBean(DataSource.class)
@ConditionalOnClass(JdbcTemplate.class)
```

`@ConditionalOnBean` 依赖 Bean 定义注册的**顺序**，极其容易踩。判断条件能用 `@ConditionalOnClass` 就用它。

### 4. 打包报 `Failed to replace ... jar`

服务还开着，jar 被 JVM 占用，Windows 下无法 rename。

**打包前先停服。** 停服 → `mvn package` → 启动，形成固定顺序。

### 5. Maven 报 `AccessDeniedException` 写 `.lastUpdated`

`~/.m2` 是符号链接或权限受限时，Maven 写失败标记文件会失败，然后整个构建中断。

```bash
mvn -Dmaven.repo.local=D:/dev-tools/apache-maven-3.9.14/repository package
```

### 6. `mvn: command not found`（明明加了 PATH）

Git Bash 里 `D:/dev-tools/...` 会被冒号当成 PATH 分隔符拆断。

```bash
# 错
export PATH="$PATH:D:/dev-tools/apache-maven-3.9.14/bin"
# 对
export PATH="$PATH:/d/dev-tools/apache-maven-3.9.14/bin"
```

### 7. 启动失败：模块清单解析不了

`baseplate.registry-fail-fast=true`（默认）时，清单里任何一个字段不合规都会导致启动失败。

常见原因：YAML 缩进错、`id` 与已有模块重复、`depends-on` 指向不存在的模块、
`flyway-locations` 路径不存在。日志里会给出具体模块名。

**不要为了启动成功把 fail-fast 关掉**，那是把问题推到运行期。

---

## 二、Spring 与 Web

### 8. 控制器类上有 `@RequestMapping`，接口却 404

Spring Framework 7 移除了「只有 `@RequestMapping` 就算控制器」的行为。

```java
@RequestMapping("/api/crm/customers")
public class CustomerController { ... }        // ❌ 404

@RestController
@RequestMapping("/api/crm/customers")
public class CustomerController { ... }        // ✅
```

编译不报错，只在运行期 404。曾经为了修这个，误把控制器改成自动配置注册 Bean，越走越远。

### 9. 扫描 `@OpenApi` 拿不到完整路径

自己反射找注解，只能拿到方法上的 `@GetMapping("/x")`，拿不到类级前缀拼好的最终路径。

正确做法是用 `RequestMappingHandlerMapping.getHandlerMethods()`——只有在它那里路径才拼完。

### 10. 启动期扫描时 `getHandlerMethods()` 是空的

时机太早。`RequestMappingHandlerMapping` 在 `afterPropertiesSet` 里才 `initHandlerMethods`。

用 `SmartInitializingSingleton`（所有单例就绪后、Web 容器接受请求前），不要用 `@PostConstruct` 或 `ApplicationRunner` 之前。

### 11. 幂等拦截器装了两遍

`WebMvcConfig` 同时被组件扫描和自动装配注册，拦截器被加两次，同一个请求消费两次 nonce。

去掉 `@Configuration` 注解（只保留自动配置里的 `@Bean`），或者确保只注册一次。

---

## 三、JDBC 与 SQL

### 12. `GeneratedKeyHolder.getKey()` 抛 "multiple keys"

PostgreSQL 驱动在 `Statement.RETURN_GENERATED_KEYS` 下会返回**整行**，`getKey()` 拿到多个就炸。

```java
// 错
connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
// 对
connection.prepareStatement(sql, new String[]{"id"});
```

### 13. 审计详情返回 `{"type":"jsonb","value":"..."}`

直接查 `jsonb` 列，JDBC 返回 `PGobject`，序列化后就是那个鬼样子。

```sql
-- 对：转成文本，前端再 JSON.parse
SELECT detail::text AS detail FROM aud_access_log WHERE id = ?
```

### 14. `findById(a, b)` 查出了别的租户的数据

参数顺序与 SQL 里的 `?` 没对齐。

```java
// SQL: WHERE tenant_id = ? AND id = ?
repository.findById(tenantId, id);   // ✅
repository.findById(id, tenantId);   // ❌ 类型都是 long/String 混着，编译器不报
```

同一个错误在 3 个方法里复现过。写双参数查询时，把 SQL 与实参**逐位念一遍**。

### 15. `category_ids ?| array['13']` 永远 0 命中

`?|` 是 jsonb 的「键是否存在」操作符，不是数组包含。

```sql
-- 对
WHERE category_ids @> '[13]'::jsonb
```

### 16. 老应用升级后只被授权了 1 个接口

批量补授权的逻辑写成了「逐条 `NOT EXISTS` 判断」：

```java
// 错：第一条插进去之后，后续判断就变成「已存在」了
for (String code : codes) {
    if (!exists(appId, code)) { insert(appId, code); }
}
```

正确做法是**先查出所有未授权的应用**，再对每个应用整体授权。

---

## 四、安全

### 17. 第三方 access_token 能调内部接口

开放令牌与登录 JWT 用了同一个密钥。第三方拿自己的 token 打 `/api/identity/users`，
内部 `JwtAuthenticationFilter` 验签通过，建出一个 `username=null` 的主体，等于越权。

修法：`BASEPLATE_OPEN_TOKEN_SECRET` 独立，且启动期比对同值直接失败。
**这个自检不要删。**

### 18. 换独立密钥后，`OAUTH2_HMAC` 应用调开放接口全部 401

内部 `JwtAuthenticationFilter` 跑在更外层，把第三方的 Bearer 令牌当成非法内部令牌 401 短路了。

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/openapi/");
}
```

### 19. 第三方调用在审计里整行消失

`AccessLogFilter` 按 `tenant_id` 过滤，第三方请求没有登录用户，`tenant_id`/`actor` 都是 null，
控制台按租户一过滤就整行不见了。

修法：`OpenApiAuthFilter` 把应用身份写进 request 属性，`AccessLogFilter` 据此落库：

```java
request.setAttribute("openapi.appKey", appKey);
request.setAttribute("openapi.appName", appName);
request.setAttribute("openapi.appTenant", app.tenantId());
request.setAttribute("openapi.actUser", actUser);   // 被代理的用户
```

### 20. 权限注解写了，但永远 403

清单里的编码与注解里的字符串不一致（大小写、冒号、下划线）。

两边都是 `模块:动作` 格式，改完用全局搜索对齐。**拼错不会报任何错。**

### 21. 页面把「用户身份」接口勾给了只有机器身份的应用

页面置灰只是提示。后端必须再拦一次：

```java
if (!app.supportsGrant("authorization_code")) {
    // 勾了 USER 类型的接口 → 400 并说明原因
}
```

---

## 五、序列化

### 22. 请求体缺一个字段就 400「请求体解析失败」

Jackson 3 开启了 `FAIL_ON_NULL_FOR_PRIMITIVES`。DTO 用原始类型时，字段缺失或传 `null` 直接解析失败。

```java
public record AppRequest(String name, Boolean sandbox, Integer qpsLimit) {}   // ✅
public record AppRequest(String name, boolean sandbox, int qpsLimit) {}       // ❌
```

### 23. 各种「格式不对」都显示成 `500 服务内部错误`

`HttpMessageNotReadableException` 没被单独映射，落进了 catch-all。

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
public ProblemDetail handleUnreadable(...) {
    return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
            "请求体解析失败：" + trim(e.getMostSpecificCause().getMessage()), request);
}
```

同理：`OpenTokenService.verify` 抛的 `IllegalArgumentException` 也曾被 `catch (RuntimeException)` 兜成 500。

---

## 六、Flowable

### 24. 任何 BPMN 都部署失败：`src-resolve: 无法解析 extension`

fat jar 下 XSD 从 classpath 解析失败。依次试过 `addBpmnModel`、`BpmnParseFactory`、
`EngineConfigurationConfigurer`、`SmartInitializingSingleton`，**全部无效**。

正确做法是官方提供的：

```java
repositoryService.createDeployment()
    .addInputStream(resourceName, inputStream)
    .disableSchemaValidation()      // ← 就这一行
    .deploy();
```

XML 的安全性由我们自己的 `BpmnXmlValidator` 白名单保证，不依赖 XSD。

---

### 49. 按定义统计实例数：LEFT JOIN 的空行被算成"运行中 1 次"

一条 SQL 替代"每个定义 count 两次"时，`LEFT JOIN ACT_HI_PROCINST` 在没有实例的
定义上产出一行全 NULL，`SUM(CASE WHEN END_TIME_ IS NULL ...)` 会把这一行计成 1 个
"运行中"——56 个定义凭空多出 14 个运行中，与 overview 的口径打架。
**做法**：判断里加上 `h.ID_ IS NOT NULL`（这一行真的对应一个实例）。
改完必须拿逐条 count 的旧口径交叉比对一遍。

另外 Flowable 8 的 `ManagementService` 已经没有 `createNativeQuery`，
官方替代 `executeCustomSql` 要写 MyBatis mapper；同库直查 `ACT_*` 表
（放在适配器里，注释说明换引擎时一起重写）成本低得多。


## 七、前端

### 25. 复选框勾选状态不更新

Element Plus 2.9 里 `:value` 不生效。

```vue
<!-- 错 -->
<el-checkbox :value="selected.includes(code)" @change="toggle(code)" />
<!-- 对 -->
<el-checkbox :model-value="selected.includes(code)"
             @change="(checked) => toggle(code, checked)" />
```

### 26. 鼠标悬浮时底色只包住半行

自定义 class 加到了 `el-checkbox` 根节点上，而组件自带 `height:32px` / `margin-right:30px` / `white-space:nowrap`。
两行内容被 32px 裁掉。

要么把 class 加到外层 `div`，要么显式覆盖这三条。

### 27. 自动化测试里下拉选了没反应

`dispatchEvent(new MouseEvent('click'))` 只改显示层，不改 `v-model`。
用 `page.mouse.click(x, y)` 命中真实坐标。

### 28. `vite build` 通过了，运行时 `ReferenceError`

Vite 不做未定义标识符检查，漏 import 的 `<Link>` 只在渲染时炸。

改完前端必须**逐页渲染冒烟**（`check.mjs` 就是干这个的），不能只看 build 成功。

---

## 八、状态一致性

### 29. 人工改的名字，重启后被代码覆盖

目录同步的 upsert 把 `name` 也一起更新了。

修法：库里加 `label_edited`，SQL 里条件更新：

```sql
ON CONFLICT (code) DO UPDATE SET
    name = CASE WHEN open_api.label_edited THEN open_api.name ELSE EXCLUDED.name END,
    -- 安全字段照常更新
    method = EXCLUDED.method,
    path = EXCLUDED.path
```

**展示文案可人工覆盖，安全字段（method/path/subject/permission）以代码为准。**

### 30. 点「全部授权」保存报 400

全选把置灰的接口也算进去了。

```js
// 错
granted.value = catalog.value.map(item => item.code)
// 对
granted.value = catalog.value.filter(item => !blockedApi(item)).map(item => item.code)
```

凡是「全选」都要问一句：**有没有不可选项？**

### 31. 改了 Flyway 脚本，启动报 checksum mismatch

Flyway 记录每个脚本的校验和，改历史文件必然失败。

**已经执行过的脚本一个字符都不能改。** 要改就新建一个版本。

### 32. 已结束的流程实例，详情页一片空白

原实现 `tasksOfInstance()` 用 `TaskService.createTaskQuery()` 查**运行表**。实例一结束，
运行任务就被删掉，接口必然返回 `200 []`。

监控、详情、轨迹一律走 `HistoryService`（`createHistoricTaskInstanceQuery` /
`createHistoricActivityInstanceQuery`）；运行表只用来算「当前停在哪」。

### 33. 点「驳回」和点「通过」结果一样，实例都结束了

`complete()` 只把 `decision` 写成一个流程变量。如果 BPMN 里没有排他网关去消费它，
两条线都会走到同一个结束节点。变量写了 ≠ 分支生效。
示例流程已带 `exclusiveGateway` + `${decision == 'REJECT'}` 回到发起人重做。

### 34. 驳回后「发起人重做」任务查不到

后端注入 `initiator` 时用了 `username`（`admin`），而待办查询按 `userId`（`1`）。
**流程变量里的办理人必须与 `taskCandidateOrAssigned` 用的是同一套 ID**，不能混用户名和用户 ID。

### 35. 打回提示显示「同意」，不是驳回原因

`lastReason` 取「最近一条有 reason 的操作」，被后续 APPROVE 的意见覆盖了。
按业务语义，它只能取**最后一次打回类操作**（`action=ROLLBACK` 或 `decision=REJECT`）。

### 36. 存量 BPMN 在 bpmn-js 里白屏

早期脚本部署的 XML 只有流程语义、没有 `BPMNDiagram` 坐标（562 字节）。
`BpmnDiagramEnricher` 在**返回给前端时**补一份 DI，不改库里的部署内容。
事件节点用 36×36、网关 50×50、任务 120×80，中心线对齐，否则文字会压在连线上。

### 37. 作废流程把老实例也一起冻住了

`RepositoryService.suspendProcessDefinitionById(id)` 不带参数时行为不直观。
必须显式用 `suspendProcessDefinitionById(id, false, null)`，`false` = **不挂起已有实例**。
这样新实例被引擎拒绝，老实例照常走完。实测「作废后老实例仍能办完三步」才敢说做对了。

### 38. 迁移到新版本，迁了一半才发现节点对不上

Flowable 的 `ProcessMigrationService` 不会替你判断「新版本删掉了老节点」的后果。
先自己按 `BpmnModel` 比对源/目标节点集合，逐个运行实例检查当前节点是否还在目标版本里；
**只要有一个不兼容就整体拒绝并给出清单**，不能边迁边报错。

### 39. 加了 workflow 迁移目录，却忘了把它放进启动命令

模块清单里声明了 `flyway-locations: classpath:db/migration/workflow`，
但启动脚本手抄的 `locations` 漏了这一条 → `wf_operation_log` 不建表。
写入是旁路（失败只告警），所以**流程照常跑，原因和打回次数却全是空的**，很难发现。
现在用 `bash run-server.sh --migrate` 一次性带全目录。

### 40. 验证脚本把演示账号当靶子，用户被锁在外面

`e2e.py` 里「复用 alice 并重置密码」每次运行都把 alice 的密码换成随机值、
`must_change_password` 重新置 true。用户用自己设的密码登录 → 连续失败 5 次 → 锁定 15 分钟，
而用户管理页只显示 `status=ACTIVE`，管理员看到「正常」却没任何办法解锁。
**回归脚本绝不能改动真实/演示账号**：用带时间戳的独立临时账号，收尾停用；
再补一条「跑完脚本后 alice 状态与基线全等」的断言。

### 41. 用户管理页显示「正常」，但账号其实被锁了

锁定态在 `failed_attempts` / `locked_until` 两列，不在 `status`。
`UserView` 只返回 status 与 mustChangePassword，管理员无从判断也没法解锁。
现在列表返回失败次数与锁定时点，页面显示红色「已锁定」+ 截止时间 + 解锁按钮，
并新增 `POST /api/identity/users/{id}/unlock`。**重置密码时也要顺带解锁**，
否则用户拿着新的一次性密码仍被挡在门外。

### 42. 管理员重置密码后，用户立刻登录改密却报「401 令牌已注销」

`TokenBlacklist.isUserRevoked` 用 `revokeEpochSecond >= token.iat` 判断，
而两者都是秒级：重置与登录发生在同一秒时，刚签发的新令牌被当成旧令牌误杀。
改成**严格大于**，并在**登录成功后清除用户级吊销标记**（旧令牌在换凭证那一刻已经失效，
不必再挂全局开关）。实测重置后同一秒登录 + 改密 + 再登录全部 200。

### 43. 流程图高亮把节点名称糊成一团，还出现红框套橙框

两个原因叠在一起：① CSS 写成 `.djs-visual > *`，把 `<text>` 也一起设了浅色 `fill`
和 3px `stroke`，节点名称笔画粘连；② 同一个节点既在 `currentActivities` 又在
`rolledBackActivities`，`addMarker` 加了两个 class，红框套橙框。
**做法**：高亮只对 `:is(rect, circle, ellipse, polygon)` 上色，文字单独强制
`fill:#1C1B18; stroke:none`；marker 按「当前 > 被打回 > 已走过」优先级去重，
一个节点只保留一种状态。顺带把「没有打回记录时 lastReason 取任意最新操作」去掉，
否则详情里「最近打回原因」会显示成「发起流程」。
改完前端必须**逐页渲染冒烟**（`check.mjs` 就是干这个的），不能只看 build 成功。

### 44. bpmn-js 实例塞进 `ref()` 之后，右侧面板永远「画布里还没有节点」

`ref(obj)` 会把对象包成 `reactive` 代理，`props.modeler` 传下去就是代理，
`engine.get('elementRegistry')` 在代理上取内部字段失败，异常又被 `catch` 吞掉，
界面只剩一句「画布里还没有节点」——看起来像节点没拖进去。
**做法**：`shallowRef` 存 bpmn-js 实例；`catch` 里必须 `console.error`，别静默降级成空数组。
判断这类问题的快捷方式是打开 dev 服务器（`vite`）用 `__vueParentComponent` 取 `props`，
生产包里 Vue 会剥掉这个调试钩子，会误判成"没传进来"。

### 45. `elementRegistry` 里没有 `userTask`，只有 `bpmn:UserTask`

bpmn-js 的元素类型带命名空间前缀且首字母大写；后端编译器认的是 camelCase。
过滤前要 `type.replace(/^bpmn:/, '')` 再把首字母转小写，否则永远匹配 0 条。

### 46. `zoom('fit-viewport')` 抛异常会把后面的逻辑一起带走

容器宽高为 0（tab 刚切、抽屉入场动画没结束）时缩放比算出来是 `Infinity`，
bpmn-js 抛 `SVGMatrix ... non-finite`。
① 初始化前先等容器有尺寸；② 缩放单独 try/catch——它失败不该连带把
`addMarker` 之类的高亮逻辑打断（高亮没了会被当成"状态丢了"）。

### 47. 首页被最慢的那个接口拖住，"看起来像没渲染"

`Promise.allSettled` 之后才统一赋值、`loading` 又挂在内容区上：
只要有一个接口慢（工作流定义曾经 5 秒），全页数字一起空着。
**做法**：每个数据块独立请求、独立到货独立渲染；只要"数量"的页面别调"明细"接口
（首页要的是 4 个数，走一条 SQL 的 `/overview`，而不是带每定义实例统计的 `/definitions`）。

### 48. 双向联动的死循环：面板选画布、画布又触发面板

面板 `select()` 里调 `selection.select()`，bpmn-js 会同步发 `selection.changed`，
处理器里又调 `select()`——两边互相触发直到栈溢出。
**做法**：`selection.changed` 处理器第一行判断 `currentId === element.id` 就直接 return，
这个相等判断就是循环的断点；副作用是面板点击当前项也会触发一次 `changed` 并被 return 掉，无碍。


---

## 九、通用经验

不是具体 bug，是排查方法。

1. **报错信息指向的位置，往往不是问题所在。** 500 可能来自更外层的过滤器；
   404 可能是类上少了一个注解。先看完整堆栈的第一行 `Caused by`。
2. **「看起来正常」最危险。** 权限注解拼错不报错、审计 tenant 为 null 不报错、
   参数顺序反了不报错。所以每加一个能力，都要问「如果它没生效，我会看到什么」——如果答案是「什么都没有」，就补一条断言。
3. **先问两个数字是不是同一时刻、同一数据源。** 界面显示和库里的数不一致时，八成是缓存或时序问题。
4. **改完立刻验证，不要攒。** 一次改 10 个文件再编译，报错会互相掩盖。
5. **批量文本替换最危险。** 一天之内误伤过 4 次。要用行号区间或唯一上下文，改完立刻全局搜索 + 编译。
