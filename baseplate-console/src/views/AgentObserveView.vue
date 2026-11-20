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

        <!-- system prompt 与工具定义是请求的固定前缀，整个会话只展示一次 -->
        <el-collapse class="prompt-collapse" v-if="detail.systemPrompt || toolDefs.length">
          <el-collapse-item v-if="detail.systemPrompt"
                            title="System Prompt（固定前缀，整个会话只发送一次）" name="sp">
            <pre class="prompt-text">{{ detail.systemPrompt }}</pre>
          </el-collapse-item>
          <el-collapse-item v-if="toolDefs.length"
                            :title="`Tools（${toolDefs.length} 个工具定义，固定前缀，不再逐次重复）`"
                            name="tools">
            <pre class="prompt-text">{{ prettyValue(toolDefs) }}</pre>
          </el-collapse-item>
        </el-collapse>

        <el-collapse class="runs-collapse">
          <el-collapse-item :title="`模型请求（共 ${detail.modelCalls?.length || 0} 次调用，已合并为一份完整内容）`"
                            name="calls">
            <div class="call-toolbar">
              <el-radio-group v-model="viewMode" size="small">
                <el-radio-button value="chat">对话视图</el-radio-button>
                <el-radio-button value="json">JSON</el-radio-button>
              </el-radio-group>
              <span class="hint">
                请求体是累积的，逐次展示会重复；这里只保留最完整的一份（导出仍按原始每次调用输出）
              </span>
            </div>

            <div v-show="viewMode === 'chat' && mergedMessages.length" class="chat">
              <div v-for="(message, index) in mergedMessages" :key="index"
                   class="chat-row" :class="`role-${message.role}`">
                <div class="chat-head">
                  <el-tag size="small" effect="plain" :type="roleTagType(message.role)">
                    {{ message.role }}
                  </el-tag>
                  <span v-if="message.name" class="hint">{{ message.name }}</span>
                  <span v-if="message.tool_call_id" class="hint">
                    tool_call_id: {{ message.tool_call_id }}
                  </span>
                </div>
                <div class="chat-body">
                  <div v-if="message.reasoning_content" class="chat-thinking">
                    <div class="chat-label">reasoning</div>
                    <pre class="chat-text">{{ message.reasoning_content }}</pre>
                  </div>
                  <pre v-if="contentText(message.content)" class="chat-text">{{ contentText(message.content) }}</pre>
                  <div v-for="call in message.tool_calls || []" :key="call.id" class="chat-tool">
                    <div class="chat-label">调用 {{ call.function?.name }}</div>
                    <pre class="chat-text">{{ prettyValue(call.function?.arguments) }}</pre>
                  </div>
                </div>
              </div>
            </div>

            <pre v-show="viewMode === 'json' && Object.keys(detail.mergedRequest || {}).length"
                 class="call-json">{{ prettyValue(detail.mergedRequest) }}</pre>

            <el-empty v-if="!detail.modelCalls?.length"
                      description="没有模型调用记录（旧会话未采集）" :image-size="50" />
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
  mergedRequest: Record<string, any>
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
const viewMode = ref<'chat' | 'json'>('chat')
const detailTitle = computed(() =>
  detail.value ? `会话 #${detail.value.session.id} · ${detail.value.session.title || '未命名会话'}` : '')

const mergedMessages = computed<Array<Record<string, any>>>(() => {
  const value = detail.value?.mergedRequest?.messages
  return Array.isArray(value) ? value : []
})

const toolDefs = computed<Array<Record<string, any>>>(() => {
  const value = detail.value?.mergedRequest?.tools
  return Array.isArray(value) ? value : []
})

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
    viewMode.value = 'chat'
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

/**
 * 展示值统一格式化：字符串若是 JSON（tool_calls.arguments 就是 JSON 串）就缩进展开，
 * 其余对象直接序列化；解析失败按原文显示。
 */
function prettyValue(value: unknown) {
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 消息 content 可能是字符串，也可能是多模态块数组，统一压成可读文本。 */
function contentText(content: unknown) {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((part) => (part && typeof part === 'object' && 'text' in part
        ? String((part as Record<string, any>).text) : JSON.stringify(part)))
      .join('\n')
  }
  return content ? String(content) : ''
}

function roleTagType(role: unknown) {
  switch (String(role || '')) {
    case 'user':
      return 'primary'
    case 'assistant':
      return 'success'
    case 'tool':
      return 'warning'
    default:
      return 'info'
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

.call-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.chat {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.chat-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border: 1px solid var(--bp-line);
  border-radius: 8px;
  background: #FCFCFD;
}
.chat-row.role-user { background: #F4F8FF; }
.chat-row.role-assistant { background: #F6FBF7; }
.chat-row.role-tool { background: #FFFBF2; }
.chat-row.role-system { background: #F4F4F8; }
.chat-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.chat-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chat-label {
  font-size: 11px;
  color: var(--bp-muted);
  margin-bottom: 2px;
}
.chat-thinking {
  padding: 6px 8px;
  border-left: 2px solid #D9D9E3;
  background: #FAFAFC;
  border-radius: 4px;
}
.chat-tool {
  padding: 6px 8px;
  border-left: 2px solid #E6C77A;
  background: #FFFDF7;
  border-radius: 4px;
}
.chat-text {
  margin: 0;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.call-json {
  margin: 0;
  max-height: 420px;
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
