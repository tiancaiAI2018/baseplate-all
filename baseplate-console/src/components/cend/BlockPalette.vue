<template>
  <div class="palette">
    <div class="palette-title">区块素材</div>
    <p class="hint">点击或拖拽区块到画布，AI 生成的页面也会出现在这里。</p>
    <div class="palette-grid">
      <div v-for="item in items" :key="item.type" class="palette-item" draggable="true"
           @dragstart="onDragStart(item.type)" @click="emit('add', item.type)">
        <el-icon><component :is="iconOf(item.type)" /></el-icon>
        <span>{{ item.label }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Picture, PictureFilled, Tickets, Document, VideoCamera, Files, Minus, Rank, EditPen } from '@element-plus/icons-vue'
import { allCendBlockTypes } from './cendDsl'

const items = allCendBlockTypes()

const emit = defineEmits<{ (e: 'add', type: string): void; (e: 'dragtype', type: string): void }>()

function iconOf(type: string) {
  const map: Record<string, any> = {
    hero: Picture, heading: Tickets, text: Document, image: PictureFilled,
    button: VideoCamera, card: Files, divider: Minus, spacer: Rank, form: EditPen
  }
  return map[type] || Document
}

function onDragStart(type: string) {
  emit('dragtype', type)
}
</script>

<style scoped>
.palette-title { font-weight: 700; margin-bottom: 4px; }
.palette-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
.palette-item {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  padding: 12px 4px; border: 1px solid var(--bp-line, #e5e7eb); border-radius: 10px;
  cursor: grab; font-size: 12px; background: #fff;
}
.palette-item:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
</style>
