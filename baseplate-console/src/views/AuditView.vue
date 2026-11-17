<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-desc">
          访问审计记录每次 API 调用（含 IP、耗时、User-Agent）；业务审计记录谁做了什么操作；登录日志记录成功与失败。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane label="访问审计" name="access" />
        <el-tab-pane label="业务操作" name="business" />
        <el-tab-pane label="登录日志" name="logins" />
      </el-tabs>

      <div class="toolbar">
        <template v-if="tab === 'access'">
          <el-input v-model="filters.actor" placeholder="调用方（用户或应用名）" clearable style="width: 200px" :prefix-icon="Search" />
          <el-select v-model="filters.source" placeholder="来源" clearable style="width: 150px" @change="search">
            <el-option label="内部用户" value="INTERNAL" />
            <el-option label="第三方应用" value="THIRD_PARTY" />
          </el-select>
          <el-input v-model="filters.path" placeholder="路径包含" clearable style="width: 200px" />
          <el-select v-model="filters.status" placeholder="状态码" clearable style="width: 120px" @change="search">
            <el-option label="200" value="200" />
            <el-option label="401" value="401" />
            <el-option label="403" value="403" />
            <el-option label="500" value="500" />
          </el-select>
        </template>
        <template v-else-if="tab === 'business'">
          <el-input v-model="filters.actor" placeholder="操作人" clearable style="width: 160px" :prefix-icon="Search" />
          <el-input v-model="filters.action" placeholder="动作，如 user:create" clearable style="width: 220px" />
        </template>
        <template v-else>
          <el-input v-model="filters.username" placeholder="用户名" clearable style="width: 180px" :prefix-icon="Search" />
          <el-select v-model="filters.success" placeholder="结果" clearable style="width: 140px" @change="search">
            <el-option label="成功" :value="true" />
            <el-option label="失败" :value="false" />
          </el-select>
        </template>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer"></div>
        <span class="hint">共 {{ total }} 条 · 点击行查看详情</span>
      </div>

      <el-table :data="rows" v-loading="loading" @row-click="openDetail" class="clickable-table" row-class-name="clickable">
        <template v-if="tab === 'access'">
          <el-table-column label="时间" width="165">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="调用方" width="190">
            <template #default="{ row }">
              <div class="caller">
                <span class="caller-name">{{ row.actor || '—' }}</span>
                <el-tag :type="row.source === 'THIRD_PARTY' ? 'warning' : 'info'" size="small" effect="plain">
                  {{ row.source === 'THIRD_PARTY' ? '第三方' : '内部' }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="action" label="方法" width="78" />
          <el-table-column prop="resource" label="路径" min-width="200" show-overflow-tooltip />
          <el-table-column label="API" width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.api_code" class="code-chip">{{ row.api_code }}</span>
              <span v-else class="hint">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="Number(row.result_code) < 400 ? 'success' : 'danger'" size="small">
                {{ row.result_code }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="client_ip" label="来源 IP" width="120" />
          <el-table-column label="耗时" width="82">
            <template #default="{ row }">{{ row.duration_ms != null ? row.duration_ms + 'ms' : '—' }}</template>
          </el-table-column>
        </template>
        <template v-else-if="tab === 'business'">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column prop="actor" label="操作人" width="120" />
          <el-table-column prop="action" label="动作" width="200">
            <template #default="{ row }"><span class="code-chip">{{ row.action }}</span></template>
          </el-table-column>
          <el-table-column prop="resource" label="资源" width="180" />
          <el-table-column prop="trace_id" label="TraceId" min-width="220" show-overflow-tooltip />
        </template>
        <template v-else>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column prop="username" label="用户名" width="160" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" size="small">
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="client_ip" label="IP" width="150" />
          <el-table-column prop="reason" label="失败原因" min-width="200" />
        </template>
      </el-table>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination layout="total, prev, pager, next" :total="total" :page-size="20"
                       :current-page="page" @current-change="onPage" />
      </div>
    </div>

    <el-drawer v-model="detailOpen" title="审计详情" size="640px">
      <template v-if="detail">
        <div class="detail-hero">
          <h3>
            {{ tab === 'access' ? (detail.source === 'THIRD_PARTY' ? '第三方访问记录' : '内部访问记录')
              : tab === 'business' ? '业务操作' : '登录记录' }}
          </h3>
          <div class="sub">TraceId：{{ detail.trace_id || '—' }}</div>
        </div>
        <dl class="kv">
          <template v-if="tab === 'access'">
            <dt>调用方</dt>
            <dd>
              {{ detail.actor }}
              <el-tag :type="detail.source === 'THIRD_PARTY' ? 'warning' : 'info'" size="small" effect="plain"
                      style="margin-left: 8px">
                {{ detail.source === 'THIRD_PARTY' ? '第三方应用' : '内部用户' }}
              </el-tag>
            </dd>
            <template v-if="detail.app_key">
              <dt>应用名称</dt><dd>{{ detail.app_name || '—' }}</dd>
              <dt>AppKey</dt><dd class="mono">{{ detail.app_key }}</dd>
              <dt>开放 API</dt><dd><span class="code-chip">{{ detail.api_code || '未匹配到目录' }}</span></dd>
              <dt>沙盒</dt><dd>{{ detail.sandbox === 'true' ? '是（沙盒数据域）' : '否' }}</dd>
            </template>
            <dt>方法与路径</dt><dd><span class="code-chip">{{ detail.action }}</span> {{ detail.resource }}</dd>
            <dt>状态码</dt><dd>{{ detail.result_code }}</dd>
            <dt>来源 IP</dt><dd>{{ detail.client_ip || '—' }}</dd>
            <dt>耗时</dt><dd>{{ detail.duration_ms != null ? detail.duration_ms + ' ms' : '—' }}</dd>
            <dt>User-Agent</dt><dd class="hint">{{ detail.user_agent || '—' }}</dd>
            <dt>查询参数</dt><dd class="mono">{{ detail.request_query || '—' }}</dd>
            <dt>时间</dt><dd>{{ fmt(detail.created_at) }}</dd>
          </template>
          <template v-else-if="tab === 'business'">
            <dt>操作人</dt><dd>{{ detail.actor }}</dd>
            <dt>动作</dt><dd><span class="code-chip">{{ detail.action }}</span></dd>
            <dt>资源</dt><dd>{{ detail.resource }}</dd>
            <dt>时间</dt><dd>{{ fmt(detail.created_at) }}</dd>
          </template>
          <template v-else>
            <dt>用户名</dt><dd>{{ detail.username }}</dd>
            <dt>结果</dt><dd>{{ detail.success ? '成功' : '失败' }}</dd>
            <dt>IP</dt><dd>{{ detail.client_ip || '—' }}</dd>
            <dt>原因</dt><dd>{{ detail.reason || '—' }}</dd>
            <dt>时间</dt><dd>{{ fmt(detail.created_at) }}</dd>
          </template>
        </dl>

        <template v-if="tab === 'business'">
          <h4 style="margin: 22px 0 6px">变更前</h4>
          <pre class="json">{{ pretty(detail.before_json) }}</pre>
          <h4 style="margin: 18px 0 6px">变更后 / 结果</h4>
          <pre class="json">{{ pretty(detail.after_json) }}</pre>
        </template>
        <template v-if="tab === 'access' && extraDetail">
          <h4 style="margin: 22px 0 6px">扩展信息</h4>
          <pre class="json">{{ pretty(extraDetail) }}</pre>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'

const tab = ref('access')
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const detailOpen = ref(false)
const detail = ref<any>(null)
const filters = reactive<any>({
  actor: '', path: '', status: '', source: '', action: '', username: '', success: null,
})

/** 已在上方 kv 里单独展示的键，扩展信息里不再重复一遍 */
const SHOWN_KEYS = new Set(['source', 'appKey', 'appName', 'apiCode', 'sandbox', 'query', 'ip', 'userAgent', 'durationMs'])

function fmt(value: any) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
function pretty(value: any) {
  if (value == null) return '—'
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
  }
  return JSON.stringify(value, null, 2)
}

const extraDetail = ref<any>(null)

function pickExtra(raw: any) {
  if (!raw) return null
  let value = raw
  if (typeof raw === 'string') {
    try { value = JSON.parse(raw) } catch { return null }
  }
  if (!value || typeof value !== 'object') return null
  const rest: any = {}
  Object.keys(value).forEach((key) => {
    if (!SHOWN_KEYS.has(key) && value[key] != null) rest[key] = value[key]
  })
  return Object.keys(rest).length ? rest : null
}

function params() {
  const base: any = { page: page.value, size: 20 }
  if (tab.value === 'access') {
    if (filters.actor) base.actor = filters.actor
    if (filters.path) base.path = filters.path
    if (filters.status) base.status = filters.status
    if (filters.source) base.source = filters.source
  } else if (tab.value === 'business') {
    if (filters.actor) base.actor = filters.actor
    if (filters.action) base.action = filters.action
  } else {
    if (filters.username) base.username = filters.username
    if (filters.success !== null && filters.success !== '') base.success = filters.success
  }
  return base
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get(`/api/audit/${tab.value}`, { params: params() })
    rows.value = data.items
    total.value = data.total
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function reset() {
  Object.assign(filters, {
    actor: '', path: '', status: '', source: '', action: '', username: '', success: null,
  })
  search()
}

function onTabChange() {
  page.value = 1
  rows.value = []
  load()
}

function onPage(value: number) {
  page.value = value
  load()
}

async function openDetail(row: any) {
  extraDetail.value = null
  if (tab.value === 'logins') {
    detail.value = row
    detailOpen.value = true
    return
  }
  try {
    const { data } = await http.get(`/api/audit/${tab.value}/${row.id}`)
    detail.value = data
    if (tab.value === 'access') extraDetail.value = pickExtra(data.detail)
    detailOpen.value = true
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(load)
</script>

<style scoped>
.clickable-table :deep(.el-table__row) { cursor: pointer; }
.caller { display: flex; align-items: center; gap: 6px; min-width: 0; }
.caller-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
