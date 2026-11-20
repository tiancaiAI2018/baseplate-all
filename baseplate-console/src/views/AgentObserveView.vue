<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">智能体观测</h2>
        <p class="page-desc">
          全租户的智能体会话：谁在哪个页面用了哪个 Agent，完整的对话、思考、工具调用回放，
          以及 token / 耗时指标与用户评分。用于调优与微调样本筛选。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button :icon="Download" :loading="exporting" @click="exportJson('filtered')">导出当前筛选</el-button>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="exportJson('all')">导出全部</el-button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-select v-model="filters.agentId" placeholder="全部智能体" clearable style="width: 190px" @change="search">
          <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-input v-model="filters.userId" placeholder="用户 ID" clearable style="width: 160px" :prefix-icon="Search"
                  @keyup.enter="search" />
        <el-input v-model="filters.keyword" placeholder="标题包含" clearable style="width: 200px" :prefix-icon="Search"
                  @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="spacer"></div>
        <span class="hint">共 {{ total }} 个会话 · 点击行查看完整回放</span>
      </div>

      <el-table :data="rows" v-loading="loading" class="clickable-table" row-class-name="clickable"
                @row-click="openDetail">
        <el-table-column label="最后活跃" width="165">
          <template #default="{ row }">{{ fmt(row.lastActiveAt) }}</template>
        </el-table-column>
        <el-table-column prop="agentName" label="智能体" width="140" show-overflow-tooltip />
        <el-table-column label="用户" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.userName || row.userId }}</span>
            <span v-if="row.userName" class="hint">({{ row.userId }})</span>
          </template>
        </el-table-column>
        <el-table-column prop="pageKey" label="页面" width="150" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.title || '未命名会话' }}</template>
        </el-table-column>
        <el-table-column label="消息" width="64" align="center">
          <template #default="{ row }">{{ row.messageCount }}</template>
        </el-table-column>
        <el-table-column label="轮次" width="64" align="center">
          <template #default="{ row }">{{ row.runCount }}</template>
        </el-table-column>
        <el-table-column label="tokens" width="130" align="right">
          <template #default="{ row }">
            <span class="hint">{{ row.inputTokens }} / {{ row.outputTokens }}</span>
          </template>
        </el-table-column>
        <el-table-column label="平均耗时" width="90" align="right">
          <template #default="{ row }">
            {{ row.avgLatencyMs != null ? row.avgLatencyMs + 'ms' : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="错误" width="64" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.errorCount > 0" type="danger" size="small">{{ row.errorCount }}</el-tag>
            <span v-else class="hint">0</span>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="92" align="center">
          <template #default="{ row }">
            <span class="rate-useful">👍 {{ row.usefulCount }}</span>
            <span class="rate-not">👎 {{ row.notUsefulCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="76" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="exportOne(row)">导出</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size"
                       :total="total" :page-sizes="[10, 20, 50, 100]"
                       layout="total, sizes, prev, pager, next, jumper"
                       @current-change="load" @size-change="onSizeChange" />
      </div>
    </div>

    <el-drawer v-model="detailOpen" size="64%" :title="detailTitle">
      <div v-if="detail" class="detail">
        <div class="detail-stats">
          <div class="stat"><span class="stat-num">{{ detail.session.messageCount }}</span><span class="stat-label">消息</span></div>
          <div class="stat"><span class="stat-num">{{ detail.session.runCount }}</span><span class="stat-label">模型轮次</span></div>
          <div class="stat"><span class="stat-num">{{ detail.session.inputTokens }}</span><span class="stat-label">输入 tokens</span></div>
          <div class="stat"><span class="stat-num">{{ detail.session.outputTokens }}</span><span class="stat-label">输出 tokens</span></div>
          <div class="stat"><span class="stat-num">{{ detail.session.cachedTokens }}</span><span class="stat-label">缓存命中</span></div>
          <div class="stat"><span class="stat-num">{{ detail.session.usefulCount }} / {{ detail.session.notUsefulCount }}</span><span class="stat-label">👍 / 👎</span></div>
          <div class="stat"><span class="stat-num">{{ detail.session.errorCount }}</span><span class="stat-label">错误轮次</span></div>
        </div>

        <!-- system prompt 取自最近一次实际请求体，skill 注入后的真实内容 -->
        <el-collapse class="prompt-collapse" v-if="detail.systemPrompt">
          <el-collapse-item title="System Prompt（最近一次请求实际发送的固定前缀）" name="sp">
            <pre class="prompt-text">{{ detail.systemPrompt }}</pre>
          </el-collapse-item>
        </el-collapse>

        <el-collapse class="runs-collapse">
          <el-collapse-item :title="`模型调用请求（${detail.modelCalls?.length || 0}）· 实际发送给模型 HTTP 接口的请求体`"
                            name="calls">
            <div v-for="call in detail.modelCalls || []" :key="call.id" class="call-item">
              <div class="call-head">
                <el-tag size="small" effect="plain">step {{ call.step }}</el-tag>
                <span class="call-model">{{ call.model || '—' }}</span>
              </div>
              <pre class="call-json">{{ pretty(call.requestJson) }}</pre>
            </div>
            <el-empty v-if="!detail.modelCalls?.length" description="没有模型调用记录（旧会话未采集）"
                      :image-size="50" />
          </el-collapse-item>
          <el-collapse-item :title="`逐轮指标（${detail.runs.length}）`" name="runs">
            <el-table :data="detail.runs" size="small">
              <el-table-column label="时间" width="160">
                <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'OK' ? 'success' : (row.status === 'PAUSED' ? 'info' : 'danger')"
                          size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="输入" width="70" align="right" prop="inputTokens" />
              <el-table-column label="输出" width="70" align="right" prop="outputTokens" />
              <el-table-column label="缓存" width="70" align="right" prop="cachedTokens" />
              <el-table-column label="步数" width="64" align="right" prop="steps" />
              <el-table-column label="工具" width="64" align="right" prop="toolCalls" />
              <el-table-column label="耗时" width="80" align="right">
                <template #default="{ row }">{{ row.latencyMs != null ? row.latencyMs + 'ms' : '—' }}</template>
              </el-table-column>
              <el-table-column label="首字" width="80" align="right">
                <template #default="{ row }">{{ row.ttftMs != null ? row.ttftMs + 'ms' : '—' }}</template>
              </el-table-column>
              <el-table-column label="错误" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
                  <span v-else class="hint">—</span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh, Search, Download } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'
import { ElMessage } from 'element-plus'

interface ObserveSessionRow {
  id: number
  agentCode: string
  agentName: string
  userId: string
  userName?: string
  pageKey?: string
  title?: string
  messageCount: number
  lastActiveAt?: string
  createdAt?: string
  runCount: number
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  errorCount: number
  avgLatencyMs?: number
  usefulCount: number
  notUsefulCount: number
}

interface SessionDetail {
  session: ObserveSessionRow
  systemPrompt?: string
  messages: Array<Record<string, any>>
  runs: Array<Record<string, any>>
  modelCalls: Array<Record<string, any>>
}

const agents = ref<Array<{ id: number; agentCode: string; name: string }>>([])
const rows = ref<ObserveSessionRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const exporting = ref(false)
const filters = ref({ agentId: undefined as number | undefined, userId: '', keyword: '' })

const detailOpen = ref(false)
const detail = ref<SessionDetail | null>(null)
const detailTitle = computed(() =>
  detail.value ? `会话 #${detail.value.session.id} · ${detail.value.session.title || '未命名会话'}` : '')

onMounted(async () => {
  await load()
  try {
    const { data } = await http.get('/api/agent/observe/agents')
    agents.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  }
})

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/agent/observe/sessions', {
      params: {
        agentId: filters.value.agentId,
        userId: filters.value.userId || undefined,
        keyword: filters.value.keyword || undefined,
        page: page.value,
        size: size.value
      }
    })
    rows.value = data.items || []
    total.value = Number(data.total || 0)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function reset() {
  filters.value = { agentId: undefined, userId: '', keyword: '' }
  page.value = 1
  void load()
}

/** 换每页条数时回到第一页，避免停留在超出范围的页码。 */
function onSizeChange() {
  page.value = 1
  void load()
}

async function openDetail(row: ObserveSessionRow) {
  try {
    const { data } = await http.get(`/api/agent/observe/sessions/${row.id}`)
    detail.value = data
    detailOpen.value = true
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

/**
 * 导出完整 JSON（blob 下载，不能 window.open：要带 Authorization 头）。
 * scope=all 全租户；scope=filtered 按当前筛选条件。
 * 每个会话：元数据 + calls（每次实际发给模型的 OpenAI 风格请求体）。
 */
async function exportJson(scope: 'all' | 'filtered') {
  exporting.value = true
  try {
    const withFilter = scope === 'filtered'
    const response = await http.get('/api/agent/observe/export', {
      params: {
        scope,
        agentId: withFilter ? filters.value.agentId : undefined,
        userId: withFilter ? (filters.value.userId || undefined) : undefined,
        keyword: withFilter ? (filters.value.keyword || undefined) : undefined,
        limit: 500
      },
      responseType: 'blob'
    })
    saveBlob(response, `agent-observe-${scope}-`)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    exporting.value = false
  }
}

/** 单个会话导出：结构同批量导出的单个元素。 */
async function exportOne(row: ObserveSessionRow) {
  try {
    const response = await http.get(`/api/agent/observe/sessions/${row.id}/export`, {
      responseType: 'blob'
    })
    saveBlob(response, `agent-session-${row.id}-`)
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function saveBlob(response: { data: Blob; headers: Record<string, any> }, prefix: string) {
  const disposition = String(response.headers?.['content-disposition'] || '')
  const match = disposition.match(/filename=([^;\s]+)/)
  const name = match ? match[1].trim()
    : `${prefix}${new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '')}.json`
  const url = URL.createObjectURL(new Blob([response.data], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
  const count = Number(response.headers?.['x-export-count'] || 0)
  ElMessage.success(count > 0 ? `已导出 ${count} 个会话：${name}` : `已导出：${name}`)
}

function fmt(value?: string) {
  return value ? String(value).slice(0, 19).replace('T', ' ') : '—'
}

/** 落库的请求体是 JSON 字符串，展示时格式化缩进；解析失败按原文显示。 */
function pretty(requestJson?: string) {
  if (!requestJson) return ''
  try {
    return JSON.stringify(JSON.parse(requestJson), null, 2)
  } catch {
    return requestJson
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.toolbar .spacer { flex: 1; }
.hint { font-size: 12px; color: var(--bp-muted); }
.clickable-table { cursor: pointer; }

.detail-stats {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border: 1px solid var(--bp-line);
  border-radius: 10px;
  background: var(--bp-card);
  margin-bottom: 12px;
}
.stat { display: flex; flex-direction: column; align-items: center; min-width: 64px; }
.stat-num { font-size: 16px; font-weight: 700; }
.stat-label { font-size: 11px; color: var(--bp-muted); }

.runs-collapse { margin-bottom: 14px; }
.error-text { color: #B23B3B; font-size: 12px; }

.prompt-collapse { margin-bottom: 14px; border: none; }

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.call-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}
.call-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.call-model { font-size: 12px; color: var(--bp-muted); }
.call-json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 11px;
  font-family: Consolas, Monaco, monospace;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  background: #F7F7FA;
  border: 1px solid var(--bp-line);
  border-radius: 8px;
  padding: 10px 12px;
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.rate-useful { font-size: 12px; color: #2E7D46; margin-right: 6px; }
.rate-not { font-size: 12px; color: #B23B3B; }

.prompt-collapse { border: none; }
.prompt-collapse :deep(.el-collapse-item__header) {
  font-size: 12px;
  color: var(--bp-muted);
  background: #F4F4F8;
  border-radius: 8px;
  padding: 0 10px;
}
.prompt-text {
  margin: 0;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: #F7F7FA;
  border: 1px solid var(--bp-line);
  border-radius: 8px;
  padding: 10px 12px;
}
</style>
