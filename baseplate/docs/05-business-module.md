# 05 · 新增业务模块（从零到可用）

## 本文档解决什么

照着做，你能加一个**有表、有菜单、有权限点、有接口、有审计、能开放给第三方**的业务模块，
全程不改基盘任何一行代码。示例模块叫 `crm`（客户管理）。

模板代码都是能直接编译的写法，路径与包名按实际替换即可。

> **本文的 Java 模板已实测编译通过**（2026-11-10 建临时模块跑 `mvn -pl baseplate-crm-starter -am compile`，
> 通过后移除，避免示例代码留在主工程里）。你复制过去时只需要改包名与表名。

---

## 一、先做三个决定

| 决定 | 选项 | 建议 |
|---|---|---|
| 放哪 | ① 独立 Maven 模块 ② 直接写在 `baseplate-bootstrap` 里 | 独立模块。业务线能独立发版，且不污染基盘 |
| 数据访问 | ① `JdbcTemplate` 显式 SQL ② MyBatis-Plus | 简单 CRUD 用 MyBatis-Plus；有复杂查询/统计用显式 SQL。现有基盘模块都是显式 SQL |
| 要不要写权限切面/审计代码 | 不写 | 注解即可，基盘已经织好了 |

---

## 二、Step 1：建模块

```bash
cd baseplate
mkdir -p baseplate-crm-starter/src/main/java/com/example/baseplate/crm/{domain,application,adapter/in/web,adapter/out/persistence}
mkdir -p baseplate-crm-starter/src/main/resources/META-INF
mkdir -p baseplate-crm-starter/src/main/resources/db/migration/crm
```

`baseplate-crm-starter/pom.xml`：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.example.baseplate</groupId>
    <artifactId>baseplate-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>baseplate-crm-starter</artifactId>

  <dependencies>
    <!-- 只能依赖 api：注解、模型、端口都在这里 -->
    <dependency>
      <groupId>com.example.baseplate</groupId>
      <artifactId>baseplate-api</artifactId>
    </dependency>
    <!-- 需要 Spring Web / JdbcTemplate 时显式声明，不要指望传递依赖 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
  </dependencies>
</project>
```

> 版本号交给父 POM 的 `dependencyManagement`，子模块**不要写 `<version>`**（写了反而容易版本漂移）。

在父 `pom.xml` 的 `<modules>` 里加：

```xml
<module>baseplate-crm-starter</module>
```

---

## 三、Step 2：写模块清单

`src/main/resources/META-INF/baseplate-module.yml`：

```yaml
id: crm
display-name: 客户管理
version: 1.0.0
requires: "baseplate-api >=1.0.0,<2.0.0"
root-package: com.example.baseplate.crm
enabled-by-default: true

permissions:
  - { code: "customer:view",   label: 查看客户, type: MENU }
  - { code: "customer:create", label: 新建客户, type: BUTTON }
  - { code: "customer:update", label: 编辑客户, type: BUTTON }
  - { code: "customer:delete", label: 停用客户, type: BUTTON }

menus:
  - { path: /crm/customers, label: 客户列表, permission: "customer:view", icon: User }

flyway-locations: classpath:db/migration/crm

rule-fields:
  - { name: customerLevel, label: 客户等级, type: STRING, options: [NORMAL, VIP, SVIP] }
  - { name: creditLimit,   label: 授信额度, type: NUMBER }
```

**这一步做完，权限点就已经生效了**：启动后控制台「角色权限」页会自动出现「客户管理」分组，
可以勾选分配给角色，不需要写任何 SQL，也不需要改前端。

---

## 四、Step 3：建表

`src/main/resources/db/migration/crm/V20261120100000__crm_customer.sql`：

```sql
CREATE TABLE IF NOT EXISTS crm_customer (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    level        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    phone        VARCHAR(32),
    credit_limit NUMERIC(14,2) NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_crm_customer_code UNIQUE (tenant_id, code)
);

CREATE INDEX IF NOT EXISTS idx_crm_customer_tenant_status ON crm_customer(tenant_id, status);
```

约定：

- 每张业务表都带 `tenant_id`（共享表 + 判别列是默认租户模式）。
- 主键用 **`BIGSERIAL`**，由数据库生成，插入时不传，用 `KeyHolder` 取回。
  `IdGenerator` 端口是给「业务侧必须自己生成 ID」的场景（如一次性令牌）用的，两者不要混。
- 时间列用 `TIMESTAMPTZ`，不要用 `TIMESTAMP`（跨时区会出事）。
- 迁移文件名 `V<yyyyMMddHHmmss>__说明.sql`，**已经在库里的版本不能改内容**（Flyway 校验 checksum）。

---

## 五、Step 4：仓储（显式 SQL）

`adapter/out/persistence/CustomerRepository.java`：

```java
package com.example.baseplate.crm.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CustomerRepository {

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CustomerRow(long id, String tenantId, String code, String name,
                              String level, String phone, BigDecimal creditLimit,
                              String status) {
    }

    private static final RowMapper<CustomerRow> MAPPER = (rs, i) -> new CustomerRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("code"),
            rs.getString("name"), rs.getString("level"), rs.getString("phone"),
            rs.getBigDecimal("credit_limit"), rs.getString("status"));

    /**
     * 注意参数顺序：SQL 里的 ? 与实参必须逐位对齐。
     * 历史上这里写反过一次（findById(tenant, id) 拼成 WHERE id=? AND tenant_id=?），
     * 结果租户隔离形同虚设，而且只在特定数据下才暴露。
     */
    public Optional<CustomerRow> findById(String tenantId, long id) {
        return jdbc.query("SELECT * FROM crm_customer WHERE tenant_id = ? AND id = ?",
                MAPPER, tenantId, id).stream().findFirst();
    }

    public List<CustomerRow> list(String tenantId, String keyword, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM crm_customer WHERE tenant_id = ? AND status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name ILIKE ? OR code ILIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(String tenantId, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM crm_customer WHERE tenant_id = ? AND status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name ILIKE ? OR code ILIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * 主键由数据库生成。必须用 new String[]{"id"} 指定返回列：
     * 用 Statement.RETURN_GENERATED_KEYS 时 PostgreSQL 会返回整行，
     * KeyHolder.getKey() 会抛 "multiple keys"。
     */
    public long insert(String tenantId, String code, String name, String level,
                       String phone, BigDecimal creditLimit, String createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO crm_customer(tenant_id, code, name, level, phone,"
                            + " credit_limit, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, level);
            ps.setString(5, phone);
            ps.setBigDecimal(6, creditLimit);
            ps.setString(7, createdBy);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public int update(String tenantId, long id, String name, String level, String phone,
                      BigDecimal creditLimit) {
        return jdbc.update("UPDATE crm_customer SET name = ?, level = ?, phone = ?,"
                        + " credit_limit = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                name, level, phone, creditLimit, tenantId, id);
    }

    public int disable(String tenantId, long id) {
        return jdbc.update("UPDATE crm_customer SET status = 'DISABLED', updated_at = now()"
                + " WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    public boolean codeExists(String tenantId, String code, Long excludeId) {
        Long count = excludeId == null
                ? jdbc.queryForObject("SELECT count(*) FROM crm_customer WHERE tenant_id = ? AND code = ?",
                        Long.class, tenantId, code)
                : jdbc.queryForObject("SELECT count(*) FROM crm_customer WHERE tenant_id = ? AND code = ? AND id <> ?",
                        Long.class, tenantId, code, excludeId);
        return count != null && count > 0;
    }
}
```

**两条 SQL 纪律**（都是真实踩过的）：

1. **参数顺序逐位对齐**：`WHERE tenant_id = ? AND id = ?` 就必须传 `(tenantId, id)`。
2. **列表必须显式带 `tenant_id`**，不要依赖「上层应该传了」。

---

## 六、Step 5：服务（业务规则 + 租户 + 审计粒度）

`application/CustomerService.java`：

```java
package com.example.baseplate.crm.application;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.crm.adapter.out.persistence.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final CurrentUserProvider currentUser;

    public CustomerService(CustomerRepository repository, CurrentUserProvider currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public Map<String, Object> list(String keyword, int page, int size) {
        CurrentUser user = currentUser.current();
        int limit = Math.min(100, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * limit;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", repository.list(user.tenantId(), keyword, limit, offset));
        result.put("total", repository.count(user.tenantId(), keyword));
        return result;
    }

    public CustomerRepository.CustomerRow detail(long id) {
        CurrentUser user = currentUser.current();
        return repository.findById(user.tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
    }

    @Transactional
    @AuditLog(action = "crm:create-customer", resource = "crm_customer")
    public Map<String, Object> create(CreateCommand command) {
        CurrentUser user = currentUser.current();
        if (repository.codeExists(user.tenantId(), command.code(), null)) {
            // 业务校验统一抛 IllegalArgumentException → 400 BAD_REQUEST，detail 直接给前端看
            throw new IllegalArgumentException("客户编码已存在：" + command.code());
        }
        long id = repository.insert(user.tenantId(), command.code(), command.name(),
                command.level() == null ? "NORMAL" : command.level(),
                command.phone(), command.creditLimit() == null ? BigDecimal.ZERO : command.creditLimit(),
                user.username());
        return Map.of("id", id);
    }

    @Transactional
    @AuditLog(action = "crm:update-customer", resource = "crm_customer")
    public void update(long id, UpdateCommand command) {
        CurrentUser user = currentUser.current();
        repository.findById(user.tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        repository.update(user.tenantId(), id, command.name(), command.level(),
                command.phone(), command.creditLimit());
    }

    @Transactional
    @AuditLog(action = "crm:disable-customer", resource = "crm_customer")
    public void disable(long id) {
        CurrentUser user = currentUser.current();
        repository.findById(user.tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        repository.disable(user.tenantId(), id);
    }

    public record CreateCommand(String code, String name, String level, String phone,
                                BigDecimal creditLimit) {
    }

    public record UpdateCommand(String name, String level, String phone, BigDecimal creditLimit) {
    }
}
```

要点：

- **租户从 `CurrentUser` 拿，不从请求参数拿**。前端传租户 = 越权入口。
- `@AuditLog` 加在 Service 而不是 Controller：这样开放接口、内部接口、定时任务走同一个审计点。
- `@Transactional` 只加在写方法上，且 Service 是事务边界。

---

## 七、Step 6：控制器（权限 + 参数）

`adapter/in/web/CustomerController.java`：

```java
package com.example.baseplate.crm.adapter.in.web;

import com.example.baseplate.api.annotation.RateLimit;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.crm.application.CustomerService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 类上必须有 @RestController。Spring Framework 7 移除了「只有 @RequestMapping 就算控制器」，
 * 少这个注解会静默 404，编译不报错。
 */
@RestController
@RequestMapping("/api/crm/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("customer:view")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String keyword) {
        return service.list(keyword, page, size);
    }

    @GetMapping("/{id}")
    @RequiresPermission("customer:view")
    public Object detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping
    @RequiresPermission("customer:create")
    @RateLimit(key = "crm:create-customer", limit = 20, windowSeconds = 60)
    public Map<String, Object> create(@RequestBody CreateRequest request) {
        return service.create(new CustomerService.CreateCommand(
                request.code(), request.name(), request.level(), request.phone(), request.creditLimit()));
    }

    @PutMapping("/{id}")
    @RequiresPermission("customer:update")
    public Map<String, Object> update(@PathVariable long id, @RequestBody UpdateRequest request) {
        service.update(id, new CustomerService.UpdateCommand(
                request.name(), request.level(), request.phone(), request.creditLimit()));
        return Map.of("updated", true);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("customer:delete")
    public Map<String, Object> disable(@PathVariable long id) {
        service.disable(id);
        return Map.of("disabled", true);
    }

    /**
     * 布尔/数字一律用包装类型。Jackson 3 开了 FAIL_ON_NULL_FOR_PRIMITIVES，
     * 请求体缺字段或传 null 时原始类型会直接解析失败（表现为 400 请求体解析失败）。
     */
    public record CreateRequest(String code, String name, String level, String phone,
                                BigDecimal creditLimit) {
    }

    public record UpdateRequest(String name, String level, String phone, BigDecimal creditLimit) {
    }
}
```

**到这里，你已经自动获得**：

- 登录校验（`JwtAuthenticationFilter` 在更外层）
- 首登改密拦截
- 权限校验（`PermissionAspect` 读 `@RequiresPermission`）
- 访问审计（`AccessLogFilter` 记每个请求）
- 业务审计（`@AuditLog` 记变更前后）
- 限流（`@RateLimit`）
- 统一错误体
- TraceId 贯穿

一行胶水代码都不用写，因为这些能力都是**按注解和过滤器链织入**的。

---

## 八、Step 7：挂到 bootstrap

`baseplate-bootstrap/pom.xml` 加依赖：

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>baseplate-crm-starter</artifactId>
  <version>${project.version}</version>
</dependency>
```

`application.yml` 的 Flyway 位置加一项：

```yaml
spring:
  flyway:
    locations: >-
      classpath:db/migration/baseplate,
      classpath:db/migration/identity,
      classpath:db/migration/audit,
      classpath:db/migration/platform,
      classpath:db/migration/metadata,
      classpath:db/migration/rule,
      classpath:db/migration/crm
```

启动后应该看到：

```
Migrating schema "public" to version "20261120100000 - crm customer"
权限目录已同步：7 个模块，34 个权限点      ← 模块数与权限点都 +1/+4
```

验证：

```bash
# 权限点已进目录
curl -s localhost:8080/api/identity/permissions -H "Authorization: Bearer $TOKEN" \
  | jq '.groups[] | select(.module=="crm")'

# 分给角色后才能调
curl -s -X PUT localhost:8080/api/identity/roles/1/permissions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '["customer:view","customer:create"]'

# 调接口
curl -s -X POST localhost:8080/api/crm/customers -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"code":"C001","name":"测试客户"}'
```

---

## 九、Step 8（可选）：开放给第三方

如果某个接口要给外部系统调，加注解即可，**不需要改开放平台模块**：

```java
@GetMapping("/{id}")
@RequiresPermission("customer:view")
@OpenApi(code = "crm.customer.get", name = "查询客户", group = "客户",
        subject = OpenApiSubject.CLIENT,      // 机器身份即可
        scope = "read")
public Object detail(@PathVariable long id) { ... }

@PostMapping
@RequiresPermission("customer:create")
@OpenApi(code = "crm.customer.create", name = "代客创建客户", group = "客户",
        subject = OpenApiSubject.USER,        // 必须代表真实用户
        permission = "customer:create",       // 且该用户自己有这个权限
        scope = "write")
public Map<String, Object> create(@RequestBody CreateRequest request) { ... }
```

注意三条：

1. 路径**必须**以 `/openapi/` 开头，否则启动失败。所以开放接口通常单独建一个控制器：

```java
@RestController
@RequestMapping("/openapi/v1/crm/customers")
public class OpenCustomerController { ... }
```

2. 一个方法只能映射一个路径 + 一种 HTTP 方法（`code` 是授权主键，不能一对多）。
3. `subject = USER` 时如果没有 `permission`，启动会打 WARN——那等于只校验应用授权，不校验用户权限。

启动后目录里出现 4 条基础接口 + 你的新接口，控制台「接口目录」页能看到，授权页可勾选。

---

## 十、Step 9（可选）：前端页面

见 [07-frontend.md](07-frontend.md)。最短路径：

1. `views/CustomerView.vue`
2. `router/index.ts` 加一条路由
3. `layout/ConsoleLayout.vue` 加一个菜单项
4. 菜单项用 `auth.has('customer:view')` 控制可见

---

## 十一、Step 10（可选）：暴露给智能体

如果希望 AI 助手能查询/操作这个模块，按工具类型分开做：

| 类型 | 放哪 | 约束 |
|---|---|---|
| 内部工具（只读） | 新建 `XxxTools`（`@Tool` 方法）+ `XxxToolProvider`（`AgentToolSpec`） | 代码里 `requirePermission`；只查不改 |
| 外部工具（有副作用） | 只在 `XxxToolProvider` 声明 schema，`handler = null` | 前端在 `api/agentExternal.ts` 注册执行器，带用户令牌调本模块 REST |

工具声明必须写清 `pages`（哪些页面可用）和 `permissionCode`。前端若还要展示卡片，
约定 `ui: { kind: "...", ... }`，`kind` 不要叫 `type`。

---

## 十二、检查清单

提交前逐条确认：

- [ ] `META-INF/baseplate-module.yml` 的 `id` 与包名一致，且不与已有模块重复
- [ ] 权限点在清单里声明**并且**在控制器上用了 `@RequiresPermission`，两边编码完全一致（拼错不会报错，只会永远 403）
- [ ] 所有 SQL 都带 `tenant_id`，参数顺序逐位核对
- [ ] 写方法上有 `@Transactional`
- [ ] 写操作上有 `@AuditLog`（放在 Service 层）
- [ ] 请求体 DTO 的 `boolean`/`int` 用包装类型
- [ ] 业务校验抛 `IllegalArgumentException`（→400），不要抛 `RuntimeException`（→500）
- [ ] 迁移文件名时间戳大于已有的最大值，且**没有修改过已执行过的脚本**
- [ ] `flyway-locations` 加进了 `application.yml`
- [ ] 模块加进了父 `pom.xml` 与 `baseplate-bootstrap/pom.xml`
- [ ] 启动后 `权限目录已同步` 的模块数增加了
- [ ] 用非 `SUPER_ADMIN` 账号验证过 403 是否如期
- [ ] 若加了智能体工具：内部工具只读、外部工具走 REST，`pages`/`permissionCode` 正确

---

## 十三、常见错误

| 现象 | 原因 | 修法 |
|---|---|---|
| 接口 404，类上有 `@RequestMapping` | 缺 `@RestController` | 补上（Spring 7 不再自动识别） |
| 权限永远 403 | 清单里的 code 与注解里的字符串不一致 | 全局搜索对齐；两者都是 `模块:动作` 格式 |
| 控制台看不到新菜单 | 只加了 `menus`，没在前端注册路由 | 前端要同时加路由与菜单项 |
| 启动报 `relation "crm_customer" does not exist` | `flyway-locations` 没加 | 加到 `application.yml` |
| Flyway 报 checksum mismatch | 改了已执行过的脚本 | 新建一个 `V...` 修复脚本，不要改历史文件 |
| 列表查到了别的租户数据 | SQL 漏了 `tenant_id` 条件 | 每张业务表每个查询都带 |
| 新建客户报 500「服务内部错误」 | 抛了 `RuntimeException` 或 SQL 唯一约束冲突 | 改成 `IllegalArgumentException`，或捕获后转业务错误 |
| 请求体报 400 且提示 `Cannot map null into type boolean` | DTO 用了原始类型 | 改 `Boolean`/`Integer` |
