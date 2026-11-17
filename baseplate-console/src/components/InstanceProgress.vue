<template>
  <div v-loading="loading">
    <template v-if="detail">
      <div v-if="detail.rollbackCount" class="wf-alert">
        <b>该流程已被打回 {{ detail.rollbackCount }} 次</b>
        <span>最近原因：{{ detail.lastReason || '未填写' }}<template v-if="detail.lastDecision">（{{ detail.lastDecision === 'REJECT' ? '驳回' : detail.lastDecision }}）</template></span>
      </div>
      <dl class="kv">
        <dt>状态</dt>
        <dd>
          <el-tag :type="detail.ended ? 'info' : 'success'" size="small">{{ statusText }}</el-tag>
          <span style="margin-left: 8px">{{ fmt(detail.startedAt) }} → {{ fmt(detail.endedAt) }}</span>
        </dd>
        <dt>发起人</dt><dd>{{ nameOf(detail.startUserId) }}</dd>
        <dt>耗时</dt><dd>{{ durationText }}</dd>
        <dt v-if="detail.deleteReason">结束原因</dt><dd v-if="detail.deleteReason">{{ detail.deleteReason }}</dd>
      </dl>
      <div class="toolbar" style="margin-top: 12px">
        <span class="hint">
          <span class="legend done"></span>已走过
          <span class="legend current"></span>当前节点
          <span class="legend rollback"></span>被打回过的节点
        </span>
      </div>
      <div class="wf-chart" ref="chartRef"></div>

      <h4 class="sec-title">任务轨迹</h4>
      <el-table :data="detail.tasks" size="small" max-height="260">
        <el-table-column prop="name" label="任务" width="140" />
        <el-table-column label="办理人" width="130">
          <template #default="{ row }">{{ nameOf(row.assignee) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.decision" :type="row.decision === 'REJECT' ? 'danger' : 'success'" size="small">
              {{ row.decision === 'REJECT' ? '驳回' : row.decision === 'APPROVE' ? '同意' : row.decision }}
            </el-tag>
            <el-tag v-else-if="!row.ended" type="warning" size="small">办理中</el-tag>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因 / 意见" min-width="160" show-overflow-tooltip />
        <el-table-column label="开始" width="150"><template #default="{ row }">{{ fmt(row.startedAt) }}</template></el-table-column>
        <el-table-column label="结束" width="150"><template #default="{ row }">{{ fmt(row.endedAt) }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!detail.tasks?.length" description="没有任务轨迹" :image-size="60" />

      <h4 class="sec-title">表单数据</h4>
      <template v-if="formRows.length">
        <div v-for="row in formRows" :key="row.nodeId + row.formCode" class="form-block">
          <div class="form-block-head">
            <span class="pill green">{{ row.nodeId === '__START__' ? '发起提交' : row.nodeId }}</span>
            <span class="hint">{{ row.formCode }} · {{ nameOf(row.createdBy) }} · {{ fmt(row.createdAt) }}</span>
          </div>
          <dl class="kv">
            <template v-for="(value, key) in row.data" :key="key">
              <dt>{{ fieldTitle(key) }}</dt><dd>{{ display(value) }}</dd>
            </template>
          </dl>
        </div>
      </template>
      <p v-else class="hint">该实例没有结构化表单数据。</p>

      <h4 class="sec-title">操作流水</h4>
      <el-table :data="detail.operations" size="small" max-height="220">
        <el-table-column label="动作" width="120">
          <template #default="{ row }">{{ actionText(row.action) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="120">
          <template #default="{ row }">{{ nameOf(row.operator) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmt(row.occurredAt) }}</template></el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import BpmnViewer from 'bpmn-js/lib/Viewer'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'
import { useUserDirectory } from '../composables/useUserDirectory'

const props = defineProps<{ instanceId: string }>()
const emit = defineEmits<{ (e: 'loaded', detail: any): void }>()

const { ensureLoaded, nameOf } = useUserDirectory()

const loading = ref(false)
const detail = ref<any>(null)
const formRows = ref<any[]>([])
const chartRef = ref<HTMLElement | null>(null)
let viewer: any = null

const ACTION_TEXT: Record<string, string> = {
  START: '发起', APPROVE: '同意', REJECT: '驳回', ROLLBACK: '回退', TRANSFER: '转办',
  ADD_SIGN: '加签', TERMINATE: '终止', SUSPEND: '作废定义', ACTIVATE: '恢复定义',
  MIGRATE: '版本迁移', MIGRATE_REJECTED: '迁移拒绝'
}

const statusText = computed(() => {
  if (!detail.value) return ''
  if (!detail.value.ended) return '运行中'
  const reason = detail.value.deleteReason || ''
  if (reason.includes('撤回')) return '已撤回'
  if (reason) return '已终止'
  return '已结束'
})

const durationText = computed(() => {
  const ms = detail.value?.durationMs
  if (ms == null) return detail.value?.ended ? '—' : '进行中'
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  if (seconds < 86400) return `${(seconds / 3600).toFixed(1)} 小时`
  return `${(seconds / 86400).toFixed(1)} 天`
})

function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function actionText(action: string) { return ACTION_TEXT[action] || action }
function display(value: any) {
  if (value == null) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
function fieldTitle(key: string) { return String(key) }

async function load() {
  if (!props.instanceId) return
  loading.value = true
  detail.value = null
  formRows.value = []
  ensureLoaded()
  try {
    const [{ data: d }, { data: chart }, { data: forms }] = await Promise.all([
      http.get(`/api/workflow/instances/${props.instanceId}/detail`),
      http.get(`/api/workflow/instances/${props.instanceId}/xml`),
      http.get(`/api/workflow/instances/${props.instanceId}/form-data`)
    ])
    detail.value = d
    formRows.value = forms || []
    emit('loaded', d)
    await nextTick()
    await renderChart(chart)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

async function renderChart(chart: any) {
  if (!chartRef.value || !chart?.xml) return
  if (viewer) { viewer.destroy(); viewer = null }
  chartRef.value.innerHTML = ''
  const instance = new BpmnViewer({ container: chartRef.value })
  viewer = instance
  try {
    await instance.importXML(chart.xml)
    try { instance.get('canvas').zoom('fit-viewport') } catch { /* 容器尺寸未就绪 */ }
    const registry = instance.get('elementRegistry')
    const canvas = instance.get('canvas')
    const claimed = new Set<string>()
    const markers: Record<string, string[]> = {
      'wf-current': chart.currentActivities || [],
      'wf-rollback': chart.rolledBackActivities || [],
      'wf-done': chart.completedActivities || []
    }
    for (const className of ['wf-current', 'wf-rollback', 'wf-done']) {
      for (const id of markers[className]) {
        if (claimed.has(id) || !registry.get(id)) continue
        canvas.addMarker(id, className)
        claimed.add(id)
      }
    }
  } catch {
    ElMessage.warning('该流程缺少图形信息，暂时无法绘制流程图')
  }
}

watch(() => props.instanceId, load, { immediate: true })
onBeforeUnmount(() => { if (viewer) viewer.destroy() })
defineExpose({ reload: load, detail })
</script>

<style scoped>
.sec-title { margin: 18px 0 8px; font-size: 14px; }
.wf-chart { height: 300px; border: 1px solid #DCE7E1; border-radius: 12px; background: #fff; overflow: hidden; }
.wf-alert { display: flex; flex-direction: column; gap: 4px; margin: 0 0 12px; padding: 12px 16px; background: #FCEBEA; border: 1px solid #E9B9B5; border-radius: 10px; color: #8B2A22; font-size: 13px; }
.legend { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin: 0 4px 0 12px; vertical-align: middle; }
.legend.done { background: #2E7D4F; }
.legend.current { background: #B94E18; }
.legend.rollback { background: #B3261E; }
.form-block { border: 1px solid var(--bp-line); border-radius: 10px; padding: 12px 16px; margin-bottom: 10px; background: #FBFCFB; }
.form-block-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
:deep(.wf-done .djs-visual > :is(rect, circle, ellipse, polygon)) { stroke: #2E7D4F !important; fill: #E8F4EC !important; }
:deep(.wf-current .djs-visual > :is(rect, circle, ellipse, polygon)) { stroke: #B94E18 !important; stroke-width: 3px !important; fill: #FCE9D9 !important; }
:deep(.wf-rollback .djs-visual > :is(rect, circle, ellipse, polygon)) { stroke: #B3261E !important; stroke-width: 3px !important; fill: #FBE4E2 !important; }
:deep(.wf-chart .djs-visual > text),
:deep(.wf-chart .djs-label),
:deep(.wf-chart .djs-label > text) { fill: #1C1B18 !important; stroke: none !important; font-size: 12px !important; }
</style>