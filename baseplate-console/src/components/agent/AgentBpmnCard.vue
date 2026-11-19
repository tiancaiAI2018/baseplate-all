<template>
  <div class="bpmn-card">
    <div class="bpmn-head">
      <span class="bpmn-title">{{ title || '流程图' }}</span>
      <el-button v-if="openRoute" text size="small" @click="open">打开设计器</el-button>
    </div>
    <div ref="canvasRef" class="bpmn-canvas"></div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import BpmnViewer from 'bpmn-js/lib/Viewer'

const props = defineProps<{ xml: string; title?: string; openRoute?: string }>()
const router = useRouter()
const canvasRef = ref<HTMLElement | null>(null)
let viewer: any = null

/** 用 Viewer（只读）渲染缩略图：卡片里不需要编辑能力，也不会抢画布交互。 */
async function render() {
  if (!props.xml || !canvasRef.value) return
  if (!viewer) {
    viewer = new BpmnViewer({ container: canvasRef.value })
  }
  try {
    await viewer.importXML(props.xml)
    // 容器刚插入时宽度可能还是 0，fit 失败就保持默认缩放，不影响卡片显示
    try { viewer.get('canvas').zoom('fit-viewport') } catch { /* 尺寸未就绪 */ }
  } catch {
    // 坏图留空白，不影响对话继续
  }
}

function open() {
  // 已经在该页面时 router.push 会因「重复导航」reject，忽略即可
  if (props.openRoute) router.push(props.openRoute).catch(() => undefined)
}

onMounted(async () => {
  await nextTick()
  await render()
})

watch(() => props.xml, () => render())

onBeforeUnmount(() => {
  if (viewer) viewer.destroy()
  viewer = null
})
</script>

<style scoped>
.bpmn-card {
  border: 1px solid var(--bp-line);
  background: #FFFFFF;
  border-radius: 8px;
  overflow: hidden;
}
.bpmn-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  background: #F7FAF8;
  border-bottom: 1px solid var(--bp-line);
}
.bpmn-title {
  font-size: 12px;
  color: var(--bp-ink);
  font-weight: 600;
}
.bpmn-canvas {
  height: 168px;
  background: #FFFFFF;
}
/* bpmn-js 自带的画布背景是透明，卡片里保持白底更清晰 */
.bpmn-canvas :deep(.djs-container) {
  background: #FFFFFF;
}
</style>
