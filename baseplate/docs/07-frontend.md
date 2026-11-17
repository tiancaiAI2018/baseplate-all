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
| 状态 | Pinia | 只有一个 `auth` store |
| 路由 | vue-router，**hash 模式** | 因为要和后端同一个 jar，没有 history fallback |
| 流程图 | bpmn-js | 只在 `WorkflowView.vue` 用 |

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
| `WorkflowView.vue` | 5 个 tab；bpmn-js 打包后 597 KB，已按路由懒加载 |
| `OpenAppsView.vue` | 900px 抽屉 + 6 个 tab；授权树里 USER 类型接口在应用未开通授权码时置灰 |
| `ApiCatalogView.vue` | 接口目录；启停用 `el-switch` 的 `:model-value` + `@change` |

---

## 八、构建体积

当前 `npm run build` 产物：

| 文件 | 大小 | 说明 |
|---|---|---|
| `index-*.js` | ~1.1 MB | Element Plus + Vue + 路由，gzip 后 ~378 KB |
| `WorkflowView-*.js` | ~597 KB | bpmn-js，gzip 后 ~175 KB |
| 其余页面 | 3–33 KB | 按路由懒加载 |

如果后续要优化首屏，优先做 Element Plus 按需引入（现在全量引入）。
