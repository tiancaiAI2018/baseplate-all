<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">我的待办</h1>
        <p class="page-desc">这里汇总所有需要你审批的任务；办理完成后自动进入「已办」，发起人会实时看到进度。</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" plain @click="router.push('/workflow/start')">
          <el-icon style="margin-right: 4px"><Promotion /></el-icon>去发起流程
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane name="todo">
          <template #label>
            待办任务
            <el-badge v-if="todoTotal" :value="todoTotal" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="done">
          <template #label>
            已办任务
            <el-badge v-if="doneTotal" :value="doneTotal" :max="999" type="info" class="tab-badge" />
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="toolbar">
        <el-select v-model="filter.processKey" placeholder="全部流程" clearable style="width: 200px">
          <el-option v-for="item in processOptions" :key="item.key" :value="item.key" :label="item.name" />
        </el-select>
        <el-input v-model="filter.keyword" placeholder="搜索任务名 / 业务单号" clearable style="width: 240px"
                  :prefix-icon="Search" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
        <div class="spacer"></div>
        <el-button :loading="loading" circle @click="load">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>

      <el-table v-if="tab === 'todo'" v-loading="loading" :data="rows" class="clickable"
                @row-click="(row: any) => openTask(row)">
        <el-table-column label="任务" min-width="170">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <span class="code-chip" style="margin-left: 6px">{{ row.activityId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="流程" min-width="130">
          <template #default="{ row }">{{ row.processName || row.processKey }}</template>
        </el-table-column>
        <el-table-column label="业务单号" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.businessKey || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="发起人" width="120">
          <template #default="{ row }">{{ row.startUserName || row.startUserId || '—' }}</template>
        </el-table-column>
        <el-table-column label="到达时间" width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="已等待" width="110">
          <template #default="{ row }">
            <span :class="{ 'wait-long': waitMs(row.createdAt) > 86400000 }">{{ waitText(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click.stop="openTask(row)">办理</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">🎉</div>
            太棒了，当前没有需要你办理的任务
          </div>
        </template>
      </el-table>

      <el-table v-else v-loading="loading" :data="rows" class="clickable"
                @row-click="(row: any) => openDone(row)">
        <el-table-column label="任务" min-width="150">
          <template #default="{ row }"><b>{{ row.name }}</b></template>
        </el-table-column>
        <el-table-column label="流程" min-width="130">
          <template #default="{ row }">{{ row.processName || row.processKey }}</template>
        </el-table-column>
        <el-table-column label="业务单号" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.businessKey || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="发起人" width="110">
          <template #default="{ row }">{{ row.startUserName || row.startUserId || '—' }}</template>
        </el-table-column>
        <el-table-column label="我的决策" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.decision" :type="row.decision === 'REJECT' ? 'danger' : 'success'" size="small">
              {{ row.decision === 'REJECT' ? '驳回' : row.decision === 'APPROVE' ? '同意' : row.decision }}
            </el-tag>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="意见" min-width="150" show-overflow-tooltip />
        <el-table-column label="办结时间" width="160">
          <template #default="{ row }">{{ fmt(row.endedAt) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ durationText(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click.stop="openDone(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">🗒️</div>
            还没有办理过任务
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination background layout="total, sizes, prev, pager, next" :total="total"
                       v-model:current-page="page" v-model:page-size="size"
                       :page-sizes="[10, 20, 50]" @current-change="load" @size-change="onSizeChange" />
      </div>
    </div>

    <TaskHandleDrawer v-model="handleOpen" :task-id="activeTaskId" :done-info="doneInfo" @handled="onHandled" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, Refresh, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'
import { useWorkflowStore } from '../stores/workflow'
import TaskHandleDrawer from '../components/TaskHandleDrawer.vue'

const router = useRouter()
const workflow = useWorkflowStore()

const tab = ref<'todo' | 'done'>('todo')
const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const todoTotal = ref(0)
const doneTotal = ref(0)
const page = ref(1)
const size = ref(20)
const filter = ref({ processKey: '', keyword: '' })
const catalog = ref<any[]>([])

const handleOpen = ref(false)
const activeTaskId = ref('')
const doneInfo = ref<any>(null)

const processOptions = computed(() => {
  const map = new Map<string, string>()
  for (const item of catalog.value) map.set(item.processKey, item.name)
  for (const row of rows.value) if (row.processKey && !map.has(row.processKey)) map.set(row.processKey, row.processName || row.processKey)
  return [...map.entries()].map(([key, name]) => ({ key, name }))
})

function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function waitMs(value: any) { return value ? Date.now() - new Date(value).getTime() : 0 }
function waitText(value: any) {
  const ms = waitMs(value)
  if (ms < 60000) return '刚刚'
  if (ms < 3600000) return `${Math.floor(ms / 60000)} 分钟`
  if (ms < 86400000) return `${Math.floor(ms / 3600000)} 小时`
  return `${Math.floor(ms / 86400000)} 天`
}
function durationText(ms: any) {
  if (ms == null) return '—'
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  if (seconds < 86400) return `${(seconds / 3600).toFixed(1)} 小时`
  return `${(seconds / 86400).toFixed(1)} 天`
}

async function load() {
  loading.value = true
  try {
    const endpoint = tab.value === 'todo' ? '/api/workflow/tasks/todo' : '/api/workflow/tasks/done'
    const { data } = await http.get(endpoint, {
      params: {
        page: page.value, size: size.value,
        processKey: filter.value.processKey || undefined,
        keyword: filter.value.keyword.trim() || undefined
      }
    })
    rows.value = data.items || []
    total.value = data.total || 0
    if (tab.value === 'todo') todoTotal.value = total.value
    else doneTotal.value = total.value
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { loading.value = false }
}

async function loadCatalog() {
  try {
    const { data } = await http.get('/api/workflow/catalog')
    catalog.value = data || []
  } catch { catalog.value = [] }
}

function search() { page.value = 1; load() }
function resetFilter() { filter.value = { processKey: '', keyword: '' }; search() }
function onTabChange() { page.value = 1; load() }
function onSizeChange() { page.value = 1; load() }

function openTask(row: any) {
  doneInfo.value = null
  activeTaskId.value = row.taskId
  handleOpen.value = true
}
function openDone(row: any) {
  doneInfo.value = { decision: row.decision, reason: row.reason, endedAt: row.endedAt }
  activeTaskId.value = row.taskId
  handleOpen.value = true
}

async function onHandled() {
  await Promise.allSettled([load(), workflow.refreshTodo()])
}

onMounted(() => { load(); loadCatalog() })
</script>

<style scoped>
.tab-badge { margin-left: 6px; vertical-align: 2px; }
.tab-badge :deep(.el-badge__content) { border: none; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.wait-long { color: #A94C1F; font-weight: 600; }
</style>