# 09 · 验证与回归

## 本文档解决什么

改完怎么证明没坏、新增功能要补哪些断言、什么叫「通过」。

---

## 一、验证资产全景

| 脚本 | 位置（相对 `baseplate/`） | 断言数 | 覆盖什么 |
|---|---|---|---|
| `e2e.py` | `../../build/e2e.py` | 36 | 全链路：登录→首登改密→用户/角色/组织→开放平台→规则→低代码→BPMN→审计 |
| `verify_v2.py` | `../../build/verify_v2.py` | 35 | 权限目录 CRUD、审计详情、接口目录 CRUD、工作流查询 |
| `verify_openapi.py` | `../../build/verify_openapi.py` | 24 | 注解目录、主体区分、授权码全链路、refresh 轮换、越权拒绝 |
| `verify_lock.py` | `../../build/verify_lock.py` | 14 | 账号锁定/解锁、重置后立刻改密、演示账号 alice 基线不变 |
| `verify_workflow.py` | `../../build/verify_workflow.py` | 48 | 流程全版本、无 DI 补图、已结束实例详情、驳回分支与打回次数、作废只挡新实例、迁移预检/执行/整体拒绝、终止 |
| `verify_workflow_platform.py` | `../../build/verify_workflow_platform.py` | 38 | 节点配置按版本落库、找人逻辑、会签 60%、网关两分支、决策必填原因、表单绑定与回填、素材库、跨租户隔离 |
| `check.mjs` | `../../build/ui-check/check.mjs` | 30 张截图 | 逐页渲染 + console/pageerror 收集 |
| `openapi-check.mjs` | `../../build/ui-check/openapi-check.mjs` | 7 张截图 | 开放平台专题页 + 点同意后的真实跳转 |
| `workflow-check.mjs` | `../../build/ui-check/workflow-check.mjs` | 5 张截图 | 部署策略弹窗、定义详情图、实例详情图、打回实例红色高亮 |
| `lock-check.mjs` | `../../build/ui-check/lock-check.mjs` | 1 张截图 | 用户管理页的「已锁定 / 待改密 / 解锁」状态 |
| `platform-check.mjs` | `../../build/ui-check/platform-check.mjs` | 26 条断言 / 12 张截图 | 拖拽表单设计器能出控件、节点属性面板逐节点配置、定义详情节点配置表、编辑带回画布、演练页跑通完整链路 |
| `linkage-check.mjs` | `../../build/ui-check/linkage-check.mjs` | 10 条断言 / 3 张截图 | 设计页画布 ⇄ 节点面板双向联动：点列表画布亮出选中框并滚过去，点画布面板自动选中对应行，切换选择互斥 |
| `perf-check.mjs` | `../../build/ui-check/perf-check.mjs` | 7 条断言 | 首页性能回归：不再请求 definitions、走 overview、最慢接口 <800ms、把 overview 故意拖慢 3 秒其它卡片照常渲染 |
| `verify-docs.py` | `../../build/verify-docs.py` | 5 组 | **文档与代码一致性**：md 互引、端点集合、关键文件路径 |

> 脚本都在 `创意/build/`，不在 `baseplate/`。原因：它们最初是给同一个工作区里多个项目共用的。

### Java 单元测试（9 个文件，当前未跑）

| 文件 | 类型 | 说明 |
|---|---|---|
| `InMemory*TckTest.java` × 6 | TCK | 内存实现跑端口契约 |
| `RealPostgresMigrationTest` | 集成 | 需要真 PG，校验迁移可执行 |
| `PomBoundaryTest` | 架构 | 守住 `api` 与 `domain` 的依赖方向 |
| `ReverseCompileProbeTest` | 架构 | 反向编译探针 |

**这 9 个文件存在，但当前没有跑过，不能算通过。** 见第六节。

---

## 二、跑之前

1. 服务已启动且健康：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18081/actuator/health   # 期望 200
```

2. 数据库已迁移到最新版本（脚本会建表写数据，缺表直接炸）。
3. 账号是 `admin` 且**已经改过初始密码**（脚本里写的是 `Bp@2026!Secure`）。
4. `BASE` 变量指向正确的端口。三个 Python 脚本顶部都有：

```python
BASE = "http://localhost:18081"
```

改了端口要同步改这里。

---

## 三、怎么跑

```bash
cd 创意

# 全链路
python build/e2e.py

# 账号锁定与重置密码链路
python build/verify_lock.py

# 基座能力
python build/verify_v2.py

# 开放平台
python build/verify_openapi.py

# 工作流版本治理（会新建带时间戳的独立流程 Key，不污染既有演示数据）
python build/verify_workflow.py

# 工作流平台化：节点配置 / 找人 / 会签 / 网关 / 决策校验 / 表单绑定 / 素材库 / 租户隔离
python build/verify_workflow_platform.py

# 逐页截图（需要 puppeteer-core + 本机 Edge）
cd build/ui-check
node check.mjs
node openapi-check.mjs
node workflow-check.mjs
node lock-check.mjs
node platform-check.mjs
node linkage-check.mjs
node perf-check.mjs

# 文档与代码一致性（改完接口/文档后跑）
cd 创意
python build/verify-docs.py
```

输出约定：每条 `PASS` / `FAIL`，末尾 `==== n/m passed ====`。
脚本自带清理（会删掉自己创建的应用）。退出码非 0 表示有失败。

### 智能体怎么验（当前无脚本，按清单手工验）

目前 `build/` 下没有 `verify_agent.py`。改完智能体相关代码，至少按下面顺序过一遍：

| # | 操作 | 期望 |
|---|---|---|
| 1 | 启动日志 | `智能体工具注册完成: 14 个`；数量对不上说明 provider 没被扫描或工具重名 |
| 2 | `GET /api/agent/definitions` | 返回定义；模型密钥字段不出现 |
| 3 | `GET /api/agent/definitions/{id}/tools` | 14 个工具都在；内置工具 `editable=false` |
| 4 | 流程图页问「我有哪些待办」 | SSE 出现 `tool_call → tool_result → ui`；消息里出现任务卡片 |
| 5 | 流程设计器问「看看我现在画的流程」 | 出现 `external_tool_call`，前端执行后回传，再出现 `ui` 和 `workflow_preview` 卡片 |
| 6 | 表单设计器问「看看我当前这个表单」 | `external_tool_call` 返回 `code/name/fields`；字段与画布一致 |
| 7 | 「帮我同意第一条待办」 | 先 `workflow_list_my_tasks`、再 `workflow_task_detail`、最后 `workflow_complete_task`；待办列表自动刷新 |
| 8 | 把某工具审批策略设为 `ASK` 后触发 | 流停住并出现 HITL 卡；同意后同一气泡继续 |
| 9 | 换页继续旧会话 | 不报 `Tool not found`；`agent_session.page_key` 已更新为当前页面 |
| 10 | 恢复流（刷新页面后继续） | 历史里的 `ui` 卡片、HITL 审批卡能重新出现 |

> 自动化脚本可以照 `verify_workflow.py` 的 harness 写：用 `requests` 流式读取 SSE，
> 断言事件序列（`tool_call` → `external_tool_call` → `ui`），再断言话题/卡片字段。

### 截图产物

`build/ui-check/shots/`，命名规则 `序号-页面.png`。当前 30+7+5+1 张（全量 / 开放平台专题 / 工作流专题，`40~44` 是工作流）；
平台化专题另存 `build/ui-check/shots-platform/`，`p1~p12`，附 `report.json`（逐条断言）。

---

## 四、加新断言

三个 Python 脚本用的是同一个极简 harness：

```python
ok = []

def check(name, cond, extra=""):
    ok.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  <<< {extra}"))

# ... 断言 ...

passed = sum(1 for _, v in ok if v)
print(f"\n==== {passed}/{len(ok)} passed ====")
```

`extra` 只在失败时打印，放当时的实际值，方便定位——**不要写 `extra=""` 了事**，
失败时看不到现场等于白写。

### 什么值得写断言

| 值得 | 不值得 |
|---|---|
| 安全边界（越权、跨租户、未授权） | 纯 getter |
| 状态机流转（重放、轮换、失效） | 常量值 |
| 幂等 / 唯一约束 | 日志文案 |
| 契约（返回体字段名、错误码） | 中间变量 |
| 升级兼容（老数据补默认值） | |

### 命名

用**中文动宾短语描述期望行为**，不要写「测试1」：

```python
check("机器令牌调用户身份接口 → 403 SUBJECT_REQUIRED", ...)   # 好
check("test_userinfo", ...)                                   # 差
```

### 新增功能的例子

加了 `crm` 模块后，`verify_v2.py` 里补：

```python
# crm 模块
r = requests.post(f"{BASE}/api/crm/customers", headers=H,
                  json={"code": "T001", "name": "断言客户"})
check("可创建客户", r.status_code == 200, r.text[:150])
cid = r.json().get("id")

r = requests.get(f"{BASE}/api/crm/customers/{cid}", headers=H)
check("可查询客户", r.status_code == 200 and r.json().get("name") == "断言客户", r.text[:150])

r = requests.post(f"{BASE}/api/crm/customers", headers=H,
                  json={"code": "T001", "name": "重复编码"})
check("重复编码被拒", r.status_code == 400 and "已存在" in r.text, r.text[:150])

r = requests.delete(f"{BASE}/api/crm/customers/{cid}", headers=H)
check("可停用客户", r.status_code == 200, r.text[:150])
```

---

## 五、UI 截图脚本怎么写

`check.mjs` 的骨架：

```js
import puppeteer from 'puppeteer-core'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:18081'

const errors = []
const page = await browser.newPage()
page.on('pageerror', e => errors.push('PAGEERROR ' + e.message))
page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text()) })

await login()                       // 用原生 setter + 派发 input 事件填表单
await page.goto(`${BASE}/#/crm/customers`, { waitUntil: 'networkidle0' })
await page.waitForFunction(() => document.querySelectorAll('.el-table__row').length > 0)
await page.screenshot({ path: 'shots/40-crm.png' })
```

四个关键点：

1. **Vue 受控输入**要用原生 setter：

```js
const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
setter.call(input, 'admin')
input.dispatchEvent(new Event('input', { bubbles: true }))
```

2. **表格数据是异步的**，先 `waitForFunction` 等行出现再截图。
3. **下拉要真实鼠标**：`page.mouse.click(x, y)`，`dispatchEvent` 不改 `v-model`。
4. **失败要能被发现**：`errors` 数组非空时 `process.exitCode = 1`，否则截图空白也会「通过」。

---

## 六、什么叫「通过」

按可信度从高到低：

| 等级 | 证据 | 能说明什么 |
|---|---|---|
| A | 真实 HTTP + 真库 + 断言 | 功能确实通了 |
| B | 真实浏览器截图 + console 无错误 | 页面能渲染、数据对 |
| C | 编译通过 + 启动成功 | 语法对，别的都说明不了 |
| D | 单元测试通过（未跑） | 局部逻辑对 |

**当前状态**：

- A 级：`e2e.py` 36、`verify_v2.py` 35、`verify_openapi.py` 24、`verify_workflow.py` 48、`verify_lock.py` 14、`verify_workflow_platform.py` 38 —— **已跑，全绿（合计 195 条）**
- B 级：`check.mjs` 30 张、`openapi-check.mjs` 7 张、`workflow-check.mjs` 5 张、`lock-check.mjs` 1 张、`platform-check.mjs` 26 条 / `linkage-check.mjs` 10 条、`perf-check.mjs` 7 条 —— **已跑，console 全空**
- C 级：`mvn -DskipTests package` —— 已跑
- D 级：9 个 Java 测试文件 —— **未跑**

所以要说「端到端跑通了」，不能说「测试都过了」。

### 什么时候必须重跑

| 改动 | 至少要跑 |
|---|---|
| 任意后端代码 | `e2e.py` |
| 权限 / 审计 / 目录 | `verify_v2.py` |
| 开放平台任何东西 | `verify_openapi.py` |
| 工作流 / BPMN / 流程定义 / 实例 | `verify_workflow.py` + `workflow-check.mjs` |
| 工作流节点配置 / 表单绑定 / 素材库 / 租户隔离 | `verify_workflow_platform.py` + `platform-check.mjs` |
| 定义列表 / 首页性能 | `perf-check.mjs` |
| 设计器联动（点列表画布要亮） | `linkage-check.mjs` |
| 登录 / 密码 / 锁定 / 重置 | `verify_lock.py` + `lock-check.mjs` |
| 前端页面 | `check.mjs` |
| 打包前 | 全部脚本 |

---

## 七、常见失败原因

| 现象 | 原因 |
|---|---|
| 全部断言 401 | 服务没起，或 `BASE` 端口不对 |
| 登录返回 `mustChangePassword: true` | 账号没改过初始密码，脚本会卡在改密 |
| 400「密码不符合策略」 | 测试密码含用户名（`admin`），换成 `Bp@2026!Secure` |
| `relation "xxx" does not exist` | 迁移没跑 |
| 开放平台全 403 `API_NOT_GRANTED` | 应用没被授权该接口 |
| 429「换令牌过于频繁」 | token 端点限流 60/min，脚本连续跑太多次，等一分钟 |
| 截图空白 | 缺 `waitForFunction` 等渲染，或 headless 少了 `--virtual-time-budget` |
| `CLICK_NOT_FOUND` | 按钮文案改了，脚本里的文本要同步 |

---

## 八、还不能证明的事

诚实列一下，别把「跑过了」当「没问题」：

- **只验证了单实例**。多实例下限流、幂等、会话的分布式行为没有压测。
- **没有并发测试**。授权码的原子消费只做了逻辑验证，没有真正并发打同一个 code。
- **没有性能基线**。不知道单机能扛多少 QPS。
- **没有故障注入**。Redis 挂掉、PG 主从切换、Flowable 卡住这些场景只有降级开关，没有实测。
- **UI 是截图级验收**，不是点击路径的自动化断言；bpmn-js 的拖拽交互没有浏览器级验证。
