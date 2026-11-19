<template>
  <div class="progress-card">
    <div class="progress-head">
      <div class="progress-title">
        <span class="dot" :class="ended ? 'is-ended' : 'is-running'"></span>
        <span class="name" :title="title">{{ title || '流程进度' }}</span>
        <el-tag size="small" effect="plain" :type="ended ? 'info' : 'success'">{{ status }}</el-tag>
      </div>
      <el-button text size="small" @click="emit('open', instanceId)">查看详情</el-button>
    </div>

    <div v-if="currentNodes?.length" class="progress-current">
      当前节点：{{ currentNodes.join('、') }}
    </div>

    <div ref="canvasRef" class="progress-canvas"></div>

    <div v-if="loading" class="progress-hint">正在加载流程图…</div>
    <div v-else-if="error" class="progress-hint is-error">{{ error }}</div>
    <div v-else class="progress-legend">
      <span class="legend done"></span>已走过
      <span class="legend current"></span>当前
      <span class="legend rollback"></span>被打回
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BpmnViewer from 'bpmn-js/lib/Viewer'
import http from '../../api/client'

const props = defineProps<{
  instanceId: string
  title?: string
  status?: string
  currentNodes?: string[]
}>()
const emit = defineEmits<{ (e: 'open', instanceId: string): void }>()

const canvasRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const error = ref('')
const ended = ref(false)
let viewer: any = null

// 后端没有给状态时按运行中展示，真实状态由 /detail 回来后再纠正
const status = computed(() => props.status || (ended.value ? '已结束' : '运行中'))

/**
 * 图和状态都来自已有接口：/instances/{id}/xml 返回图与三组要高亮的节点，
 * /instances/{id}/detail 返回是否结束。卡片不自己推算进度。
 */
async function render() {
  if (!props.instanceId || !canvasRef.value) return
  loading.value = true
  error.value = ''
  try {
    const [{ data: chart }, { data: detail }] = await Promise.all([
      http.get(`/api/workflow/instances/${props.instanceId}/xml`),
      http.get(`/api/workflow/instances/${props.instanceId}/detail`)
    ])
    ended.value = !!detail?.ended
    if (!chart?.xml) {
      error.value = '该流程缺少图形信息，无法绘制流程图'
      return
    }
    if (viewer) {
      viewer.destroy()
      viewer = null
    }
    canvasRef.value.innerHTML = ''
    viewer = new BpmnViewer({ container: canvasRef.value })
    await viewer.importXML(chart.xml)
    try {
      viewer.get('canvas').zoom('fit-viewport')
    } catch {
      // 容器尺寸未就绪时保持默认缩放，不影响卡片显示
    }
    const registry = viewer.get('elementRegistry')
    const canvas = viewer.get('canvas')
    const claimed = new Set<string>()
    const markers: Record<string, string[]> = {
      'wf-done': chart.completedActivities || [],
      'wf-current': chart.currentActivities || [],
      'wf-rollback': chart.rolledBackActivities || []
    }
    // 同一个节点可能同时出现在多组里，先标记的优先，避免颜色被后写的覆盖
    for (const className of ['wf-current', 'wf-rollback', 'wf-done']) {
      for (const id of markers[className]) {
        if (claimed.has(id) || !registry.get(id)) continue
        canvas.addMarker(id, className)
        claimed.add(id)
      }
    }
  } catch (e: any) {
    error.value = e?.response?.data?.detail || '流程图加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await nextTick()
  await render()
})

watch(() => props.instanceId, async () => {
  await nextTick()
  await render()
})

onBeforeUnmount(() => {
  if (viewer) viewer.destroy()
  viewer = null
})
</script>

<style scoped>
.progress-card {
  border: 1px solid var(--bp-line);
  background: #FFFFFF;
  border-radius: 8px;
  overflow: hidden;
}
.progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  background: #F7FAF8;
  border-bottom: 1px solid var(--bp-line);
}
.progress-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.progress-title .name {
  font-size: 12px;
  font-weight: 600;
  color: var(--bp-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}
.dot.is-running { background: #2E7D4F; }
.dot.is-ended { background: #9AA5A0; }
.progress-current {
  padding: 5px 10px 0;
  font-size: 12px;
  color: #B94E18;
}
.progress-canvas {
  height: 200px;
  background: #FFFFFF;
}
.progress-canvas :deep(.djs-container) {
  background: #FFFFFF;
}
.progress-hint {
  padding: 6px 10px 8px;
  font-size: 12px;
  color: var(--bp-muted, #6B7671);
}
.progress-hint.is-error {
  color: #B3261E;
}
.progress-legend {
  padding: 4px 10px 8px;
  font-size: 11.5px;
  color: var(--bp-muted, #6B7671);
}
.legend {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin: 0 4px 0 10px;
  vertical-align: middle;
}
.legend:first-child { margin-left: 0; }
.legend.done { background: #2E7D4F; }
.legend.current { background: #B94E18; }
.legend.rollback { background: #B3261E; }

/* 与 InstanceProgress 同一套配色：绿=已走过，橙=当前，红=被打回 */
:deep(.wf-done .djs-visual > :is(rect, circle, ellipse, polygon)) {
  stroke: #2E7D4F !important;
  fill: #E8F4EC !important;
}
:deep(.wf-current .djs-visual > :is(rect, circle, ellipse, polygon)) {
  stroke: #B94E18 !important;
  stroke-width: 3px !important;
  fill: #FCE9D9 !important;
}
:deep(.wf-rollback .djs-visual > :is(rect, circle, ellipse, polygon)) {
  stroke: #B3261E !important;
  stroke-width: 3px !important;
  fill: #FBE4E2 !important;
}
:deep(.progress-canvas .djs-visual > text),
:deep(.progress-canvas .djs-label),
:deep(.progress-canvas .djs-label > text) {
  fill: #1C1B18 !important;
  stroke: none !important;
  font-size: 12px !important;
}
</style>
