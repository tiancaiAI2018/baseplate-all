# 07 · 前端控制台

## 本文档解决什么

改页面、加页面、调样式。前端是纯静态 SPA，构建产物直接塞进后端 jar，没有独立部署。

---

## 一、技术栈

| 项 | 选型 | 说明 |
|---|---|---|
| 框架 | Vue 3（`<script setup>` + TS） | |
| 构建 | Vite | |
| UI | Element Plus + `@element-plus/icons-vue` | |
| 状态 | Pinia | `auth` / `agent` / `workflow` 三个 store |
| 路由 | vue-router，**hash 模式** | 因为要和后端同一个 jar，没有 history fallback |
| 流程图 | bpmn-js | 设计器用 `Modeler`，详情/卡片用 `Viewer` |
| 表单 | form-create + `@form-create/designer` | 表单设计器与只读渲染共用同一套规则 |
| 自动排版 | `@dagrejs/dagre` | 左侧到右侧分层；回退边从节点下方绕行 |

目录在 `../baseplate-console`（与 `baseplate/` 同级）。

---

## 二、加一个页面：五步

以「客户列表」为例。

### 1. 建视图

`src/views/CustomerView.vue`：

```vue
<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">客户管理</h2>
        <p class="page-desc">维护客户档案，停用后不再出现在列表里。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建客户</el-button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索名称或编码" clearable
                  style="width: 220px" :prefix-icon="Search" @keyup.enter="load" />
        <div class="spacer"></div>
        <span class="hint">共 {{ total }} 条</span>
      </div>

      <el-table :data="rows" v-loading="loading" size="small">
        <el-table-column prop="code" label="编码" width="140" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="level" label="等级" width="100" />
        <el-table-column prop="phone" label="电话" width="150" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="disable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'

const rows = ref<any[]>([])
const total = ref(0)
const keyword = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    // 路径写全，不要在这里拼 /api 前缀之外的东西
    const { data } = await http.get('/api/crm/customers', {
      params: { page: 1, size: 50, keyword: keyword.value || undefined }
    })
    rows.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function openCreate() { /* 弹窗略 */ }

async function disable(row: any) {
  try {
    await ElMessageBox.confirm(`停用客户 ${row.name}？`, '确认', { type: 'warning' })
    await http.delete(`/api/crm/customers/${row.id}`)
    ElMessage.success('已停用')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

onMounted(load)
</script>
```

### 2. 注册路由

`src/router/index.ts`，加在 `ConsoleLayout` 的 children 里：

```ts
{ path: 'crm/customers', component: () => import('../views/CustomerView.vue'), meta: { title: '客户管理' } },
```

### 3. 加菜单

`src/layout/ConsoleLayout.vue`，在合适的分组里：

```vue
<el-menu-item v-if="auth.has('customer:view')" index="/crm/customers">
  <el-icon><User /></el-icon><template #title>客户管理</template>
</el-menu-item>
```

图标要从 `@element-plus/icons-vue` 导入。

### 4. 构建

```bash
cd baseplate-console
npm run build
```

### 5. 同步到后端并重新打包

```bash
cd ..
rm -rf baseplate/baseplate-bootstrap/src/main/resources/static/assets
cp -r baseplate-console/dist/assets baseplate/baseplate-bootstrap/src/main/resources/static/
cp baseplate-console/dist/index.html baseplate/baseplate-bootstrap/src/main/resources/static/index.html

cd baseplate
# 先停服，否则 jar 被占用无法替换
mvn -DskipTests package
```

> 后端 `static/` 是构建产物的同步目标。**不要手改那里的文件**，下次 `npm run build` 就覆盖了。

---

## 三、API 层约定

只有一个入口：`src/api/client.ts` 导出的 `http`（axios 实例，`baseURL: '/'`）。

| 约定 | 原因 |
|---|---|
| 页面里不 `import axios` | 令牌注入、401 跳登录、403 首登改密都在拦截器里 |
| 错误提示统一用 `errorText(error)` | 它优先取后端 `detail`，其次 `title`，最后 `message` |
| 路径写全（`/api/crm/customers`） | `baseURL` 是 `/`，没有额外前缀 |
| 不要自己拼 `Authorization` 头 | 拦截器已从 `localStorage` 取 |

```ts
import http, { errorText } from '../api/client'

try {
  const { data } = await http.get('/api/crm/customers', { params: { page: 1 } })
} catch (error) {
  ElMessage.error(errorText(error))
}
```

拦截器的两个自动行为：

- `401` → 清 token，`location.hash = '#/login'`
- `403` 且 `code === 'PASSWORD_CHANGE_REQUIRED'` → 跳 `#/change-password`

### 智能体相关的 API 模块

| 文件 | 职责 |
|---|---|
| `api/agentStream.ts` | `POST + fetch` 解析 SSE（EventSource 只支持 GET，且不能带 Authorization）；对话 / HITL / 外部结果三个入口 |
| `api/agentExternal.ts` | 外部工具执行器注册表：`toolCode → handler`，页面按需注册，未注册的工具停在「等待页面处理」 |
| `api/agentCanvas.ts` | 画布读取器注册表：设计器挂载时注册、卸载时注销，助手按 `pageKey` 读取当前未保存内容 |

---

## 三之二、AI 助手

悬浮入口在 [ConsoleLayout.vue](../../baseplate-console/src/layout/ConsoleLayout.vue)，按 `pageKey` 调
`/api/agent/chat/bootstrap` 决定是否显示；状态在 `stores/agent.ts`。

### 消息块

后端 SSE 事件被映射成消息块，`AgentAssistant.vue` 按类型渲染：

| block.type | 渲染 |
|---|---|
| `text` | `AgentMarkdown`（markdown-it + DOMPurify + highlight.js） |
| `thinking` | 可折叠「思考过程」 |
| `tool_call` / `tool_result` | 工具 chip，成功/失败着色 |
| `skill` | 「使用技能：xxx」标签 |
| `hitl` | 审批卡：同意 / 拒绝，恢复同一气泡 |
| `external_tool_call` | 页面操作卡：工具名、参数、执行状态 |
| `ui` | 自定义卡片：`kind=task_list` 任务列表；`kind=workflow_preview` 用 `AgentBpmnCard` 渲染 bpmn-js 缩略图；没有 `kind` 时退回「打开」按钮 |

### 外部工具执行器

`AgentAssistant.vue` 在模块加载时注册：

| 工具 | 前端行为 |
|---|---|
| `workflow_create_draft` | 调 drafts 接口 → 跳设计器 `?draftId=` |
| `workflow_get_canvas` | 从画布注册表读 XML + spec → 返回结果并带 `ui.kind=workflow_preview` |
| `form_create_draft` | 编译字段 → 保存草稿 → 跳表单设计器 `?code=` |
| `form_get_canvas` | 读表单设计器当前字段 → 返回字段清单 |

> 同意/驳回/打回**没有**外部工具：它们的实现只是把 `POST /tasks/{id}/complete`（或 `/rollback`）包一层，
> 模型多绕一次没有收益，还会让「谁批的」变模糊。办理统一由用户在办理抽屉确认。

执行结果通过 `/api/agent/external-calls/{callId}/result` 回传。**流进行中执行完的结果会先排队**，
等本轮流结束再提交，避免和正在进行的 SSE 抢同一会话。

### UI 卡片协议

工具结果 JSON 里带：

```json
{
  "ui": {
    "kind": "task_list",
    "title": "我的待办（2 条）",
    "tasks": [{ "taskId": "...", "name": "...", "processName": "...", "businessKey": "..." }]
  }
}
```

或：

```json
{
  "ui": {
    "kind": "workflow_preview",
    "title": "当前画布：请假审批",
    "bpmnXml": "<bpmn:definitions .../>",
    "route": "/workflow/designer"
  }
}
```

个人流程（我发起的 / 办理进度 / 概览）：

```json
{ "ui": { "kind": "instance_list", "title": "我发起且未走完的（3 个）",
          "items": [{ "instanceId": "...", "title": "COND-20261119-01",
                      "subtitle": "条件校验验证 · 停在：主管审批 · 11-19 16:35" }] } }
```

```json
{ "ui": { "kind": "instance_progress", "instanceId": "...",
          "title": "条件校验验证 · COND-261119-01", "status": "运行中",
          "currentNodes": ["主管审批"] } }
```

```json
{ "ui": { "kind": "stat_group", "title": "我的流程概览",
          "stats": [{ "label": "待办", "value": 4, "unit": "条", "hint": "等待我处理" }] } }
```

注意：

- 卡片负载里的 `kind` 不能叫 `type`：SSE 帧本身有 `type` 字段，会覆盖事件类型
- `ui` 由服务端单独推 `ui` 事件并写入历史块；外部工具结果里的 `ui` 会从模型上下文剔除
- **工具返回值可能被 SDK 再序列化一层**（`"{\"total\":3,...}"`），服务端统一剥壳后再找 `ui`；
  不剥壳的表现是「工具调用成功但卡片永远不出现」，日志里也没有异常
- `instance_progress` 卡片自己调 `/instances/{id}/xml` + `/detail` 画图（绿=已走过、橙=当前、红=被打回），
  点「查看详情」在助手面板内直接开 `InstanceDetailDrawer`，不跳页
- 任务卡片「办理」跳 `/workflow/todo?taskId=xxx`，待办页会直接打开办理抽屉

---

## 四、登录态与权限

`src/stores/auth.ts`：

| 成员 | 用途 |
|---|---|
| `auth.loggedIn` | 是否有令牌 |
| `auth.user` | 当前用户（`id/username/tenantId/roles/permissions/mustChangePassword`） |
| `auth.has(code)` | 是否拥有某权限点（`SUPER_ADMIN` 恒 true） |
| `auth.isSuperAdmin` | 是否超管 |
| `auth.login(u, p, tenant)` | 登录并写 token |
| `auth.loadMe()` | 拉当前用户 |
| `auth.logout()` | 清 token + 跳登录 |

页面里控制按钮显隐：

```vue
<el-button v-if="auth.has('customer:create')" type="primary">新建</el-button>
```

**注意：前端隐藏按钮只是体验，不是安全边界。** 真正拦截在后端 `@RequiresPermission`。

### 路由守卫（`router/index.ts`）

```
未登录 → { path: '/login', query: { redirect: 原地址 } }
有令牌但 user 没拉过 → await loadMe()，失败回登录
mustChangePassword === true → 强制 /change-password
```

`?redirect=` 是为了开放平台授权页：第三方把浏览器跳过来时用户往往没登录，登录后要能回到授权页。

---

## 五、样式系统

全部在 `src/style.css`。**新页面不要自己写一套卡片样式**，用现成的类。

### CSS 变量

| 变量 | 值 | 用途 |
|---|---|---|
| `--el-color-primary` | `#17564A` | 主色（深绿），Element Plus 主色也覆盖成这个 |
| `--el-color-danger` | `#B3261E` | 危险 |
| `--el-border-radius-base` | `8px` | 全局圆角 |
| `--bp-bg` | `#F4F6F4` | 页面底 |
| `--bp-card` | `#FFFFFF` | 卡片底 |
| `--bp-ink` | `#1B2420` | 正文 |
| `--bp-muted` | `#6E7C76` | 次要文字 |
| `--bp-line` | `#E4EAE6` | 分隔线 |
| `--bp-accent` | `#C05A2A` | 行动色（陶土橙） |
| `--bp-sidebar` | `#143A31` | 侧栏渐变起点 |
| `--bp-sidebar-2` | `#0F2C25` | 侧栏渐变终点 |
| `--bp-shadow` | `0 8px 30px rgba(23,86,74,.07)` | 卡片阴影 |

### 通用类

| 类 | 用途 |
|---|---|
| `.page` | 页面容器（最大宽 1500，居中，统一内边距） |
| `.page-head` / `.page-title` / `.page-desc` / `.page-actions` | 页头四件套 |
| `.card` | 白底卡片（阴影 + 圆角） |
| `.card-title` | 卡片内标题（带小圆点） |
| `.toolbar` / `.spacer` | 工具条：左边放筛选，`.spacer` 把右边推到尾部 |
| `.hint` | 灰色小字说明 |
| `.mono` | 等宽字体（编码、ID、路径） |
| `.code-chip` | 行内代码片段（浅底圆角） |
| `.method` + `.method.GET/.POST/.PUT/.DELETE` | HTTP 方法徽标（不同颜色） |
| `.detail-hero` | 详情页头（浅绿渐变块） |
| `.kv` | 定义列表（`<dt>标签</dt><dd>值</dd>`），详情抽屉用它 |
| `.json` | `<pre>` JSON 展示块 |
| `.perm-group` / `.perm-group-head` / `.perm-item` | 权限树分组 |
| `.avatar` | 圆形首字母头像 |

示例（详情抽屉）：

```vue
<dl class="kv">
  <dt>客户编码</dt><dd class="mono">{{ row.code }}</dd>
  <dt>名称</dt><dd>{{ row.name }}</dd>
  <dt>创建时间</dt><dd>{{ row.createdAt }}</dd>
</dl>
<pre class="json">{{ JSON.stringify(row, null, 2) }}</pre>
```

---

## 六、Element Plus 的四个坑

都是实际踩过、会浪费半天的。

### 1. `el-checkbox` 的选中绑定

Element Plus 2.9 里 `:value` 不生效，要用 `:model-value` + `@change`：

```vue
<!-- 错：勾选状态永远不更新 -->
<el-checkbox :value="selected.includes(item.code)" @change="toggle(item.code)" />

<!-- 对 -->
<el-checkbox :model-value="selected.includes(item.code)"
             @change="(checked) => toggle(item.code, checked)" />
```

### 2. 自定义 class 加在组件根节点上会打架

```vue
<!-- 危险：.perm-item 会落在 el-checkbox 根节点 -->
<el-checkbox class="perm-item" ... />
```

`el-checkbox` 自带 `height: 32px`、`margin-right: 30px`、`white-space: nowrap`。
两行内容会被 32px 裁掉，hover 底色只包住半行，看起来像贴了块灰条。

要么把 class 加到外层 `div`，要么显式覆盖这三条属性。

### 3. 表格数据是异步的

`el-table` 的 `:data` 变化后，`querySelector('.el-table__row')` 需要等一帧。
自动化测试里要 `waitForFunction(() => document.querySelectorAll('.el-table__row').length > 0)`。

### 4. 下拉选择要真实鼠标事件

自动化测试时 `dispatchEvent(new MouseEvent('click'))` 只改显示，不改 `v-model`。
用 `page.mouse.click(x, y)` 命中真实坐标，或者直接调接口准备数据后截图。

---

## 七、几处特殊页面

| 页面 | 特殊之处 |
|---|---|
| `LoginView.vue` | 左右分栏；登录成功后读 `route.query.redirect` 回跳 |
| `OAuthAuthorizeView.vue` | **独立全屏**，不在 `ConsoleLayout` 里；同意后 `window.location.href = redirectUrl` 跳出站点 |
| `WorkflowView.vue` | 5 个 tab；bpmn-js Modeler；`onMounted` 注册画布读取器给 AI；自动排版走 dagre |
| `NodeConfigPanel.vue` | 画布 ⇄ 面板双向联动；连线条件支持「简单条件 / 表达式」两种模式 |
| `FormDesignerView.vue` | form-create Designer；保存草稿/发布；`onMounted` 注册当前字段读取器给 AI |
| `FormListView.vue` | 表单定义列表：详情预览、编辑、作废/恢复（只挡新绑定）、新增跳设计器 |
| `MyTodoView.vue` | 待办/已办；读 `?taskId=` 自动打开办理抽屉；监听 `workflow:changed` 刷新 |
| `WorkflowLabView.vue` | 一键初始化演练环境（测试账号、演示流程、节点配置） |
| `AgentConfigView.vue` | 智能体配置：模型、提示词、页面多选、工具策略、技能绑定、测试连接 |
| `OpenAppsView.vue` | 900px 抽屉 + 6 个 tab；授权树里 USER 类型接口在应用未开通授权码时置灰 |
| `ApiCatalogView.vue` | 接口目录；启停用 `el-switch` 的 `:model-value` + `@change` |

---

## 八、构建体积

当前 `npm run build` 产物：

| 文件 | 大小 | 说明 |
|---|---|---|
| `index-*.js` | ~1.29 MB | Element Plus + Vue + 路由，gzip 后 ~428 KB |
| `FormDesignerView-*.js` | ~1.19 MB | form-create Designer，gzip 后 ~376 KB |
| `WorkflowView-*.js` | ~491 KB | bpmn-js Modeler，gzip 后 ~149 KB |
| `Viewer-*.js` | ~204 KB | bpmn-js Viewer（实例详情 / AI 卡片共用），按需懒加载 |
| 其余页面 | 3–40 KB | 按路由懒加载 |

如果后续要优化首屏，优先做 Element Plus 按需引入（现在全量引入）。
