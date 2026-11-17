<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">我发起的</h1>
        <p class="page-desc">你提交的全部流程都在这里：看进度、看审批意见，运行中的流程还可以撤回。</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="router.push('/workflow/start')">
          <el-icon style="margin-right: 4px"><Promotion /></el-icon>发起新流程
        </el-button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-radio-group v-model="state" @change="search">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="RUNNING">运行中</el-radio-button>
          <el-radio-button value="FINISHED">已结束</el-radio-button>
        </el-radio-group>
        <el-select v-model="processKey" placeholder="全部流程" clearable style="width: 190px" @change="search">
          <el-option v-for="item in processOptions" :key="item.key" :value="item.key" :label="item.name" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索业务单号 / 流程名" clearable style="width: 230px"
                  :prefix-icon="Search" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <div class="spacer"></div>
        <el-button :loading="loading" circle @click="load">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" class="clickable" @row-click="(row: any) => openDetail(row.id)">
        <el-table-column label="流程" min-width="170">
          <template #default="{ row }">
            <b>{{ row.processDefinitionName || row.processDefinitionKey }}</b>
            <el-tag size="small" effect="plain" type="info" style="margin-left: 6px">v{{ row.version }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务单号" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.businessKey || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="当前节点" min-width="160">
          <template #default="{ row }">
            <template v-if="!row.ended && row.currentTasks?.length">
              <el-tag v-for="task in row.currentTasks.slice(0, 2)" :key="task" size="small"
                      type="warning" effect="plain" style="margin-right: 4px">{{ task }}</el-tag>
              <span v-if="row.currentTasks.length > 2" class="hint">+{{ row.currentTasks.length - 2 }}</span>
            </template>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="打回" width="80" align="center">
          <template #default="{ row }">
            <span v-if="row.rollbackCount" class="num-warn">{{ row.rollbackCount }} 次</span>
            <span v-else class="hint">0</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="tagType(row)" size="small">{{ statusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="160">
          <template #default="{ row }">{{ fmt(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="openDetail(row.id)">详情</el-button>
            <el-button v-if="!row.ended" type="warning" link size="small" @click.stop="withdraw(row)">撤回</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">📮</div>
            还没有发起过流程，去「发起流程」提交第一单吧
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination background layout="total, sizes, prev, pager, next" :total="total"
                       v-model:current-page="page" v-model:page-size="size"
                       :page-sizes="[10, 20, 50]" @current-change="load" @size-change="onSizeChange" />
      </div>
    </div>

    <InstanceDetailDrawer v-model="detailOpen" :instance-id="activeId" allow-withdraw @withdrawn="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Refresh, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'
import { useWorkflowStore } from '../stores/workflow'
import InstanceDetailDrawer from '../components/InstanceDetailDrawer.vue'

const router = useRouter()
const workflow = useWorkflowStore()

const loading = ref(false)
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const state = ref('ALL')
const processKey = ref('')
const keyword = ref('')
const catalog = ref<any[]>([])

const detailOpen = ref(false)
const activeId = ref('')

const processOptions = computed(() => {
  const map = new Map<string, string>()
  for (const item of catalog.value) map.set(item.processKey, item.name)
  for (const row of rows.value) {
    if (row.processDefinitionKey && !map.has(row.processDefinitionKey)) {
      map.set(row.processDefinitionKey, row.processDefinitionName || row.processDefinitionKey)
    }
  }
  return [...map.entries()].map(([key, name]) => ({ key, name }))
})

function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function statusText(row: any) {
  if (!row.ended) return '运行中'
  const reason = row.deleteReason || ''
  if (reason.includes('撤回')) return '已撤回'
  if (reason) return '已终止'
  return '已结束'
}
function tagType(row: any) {
  const status = statusText(row)
  return status === '运行中' ? 'success' : status === '已撤回' ? 'warning' : status === '已终止' ? 'danger' : 'info'
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/workflow/instances/page', {
      params: {
        page: page.value, size: size.value, state: state.value, mine: true,
        processKey: processKey.value || undefined,
        keyword: keyword.value.trim() || undefined
      }
    })
    rows.value = data.items || []
    total.value = data.total || 0
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
function onSizeChange() { page.value = 1; load() }
function openDetail(id: string) { activeId.value = id; detailOpen.value = true }

async function withdraw(row: any) {
  try {
    const { value } = await ElMessageBox.prompt(
      `撤回「${row.processDefinitionName || row.processDefinitionKey}」（${row.businessKey || row.id.slice(0, 8)}）？撤回后流程立即结束。`,
      '撤回流程', { inputPlaceholder: '撤回原因（可不填）', confirmButtonText: '确认撤回', type: 'warning' })
    await http.post(`/api/workflow/instances/${row.id}/withdraw`, { reason: value || '' })
    ElMessage.success('已撤回')
    await Promise.allSettled([load(), workflow.refreshTodo()])
  } catch (error: any) {
    if (error !== 'cancel' && error?.message !== 'cancel') ElMessage.error(errorText(error))
  }
}

onMounted(() => { load(); loadCatalog() })
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.num-warn { color: #B3261E; font-weight: 600; }
</style>