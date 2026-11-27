<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ block: any }>()

const categories = computed<string[]>(() => (props.block?.categories || []).map(String))
const series = computed<any[]>(() => props.block?.series || [])
const max = computed(() => {
  const values = series.value.flatMap((item: any) => (item.data || []).map(Number))
  return Math.max(1, ...values.filter((n: number) => Number.isFinite(n)))
})

function height(value: any) {
  const n = Number(value)
  return Number.isFinite(n) ? `${Math.max(2, (n / max.value) * 100)}%` : '2%'
}
</script>

<template>
  <div class="chart-card">
    <div class="chart-title">{{ block.title || '数据图表' }}</div>
    <div v-if="!categories.length" class="chart-empty">没有可绘制的数据</div>
    <div v-else class="chart-body">
      <div v-for="(category, index) in categories" :key="index" class="chart-group">
        <div class="chart-bars">
          <div v-for="line in series" :key="line.name" class="chart-bar"
               :style="{ height: height(line.data?.[index]) }"
               :title="`${line.name || '数值'}：${line.data?.[index]}`" />
        </div>
        <div class="chart-label">{{ category }}</div>
      </div>
    </div>
    <div class="chart-legend">
      <span v-for="line in series" :key="line.name" class="legend-item">
        <i class="legend-dot" /> {{ line.name || '数值' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.chart-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-bg-color);
}
.chart-title { font-weight: 600; margin-bottom: 10px; }
.chart-empty { color: var(--el-text-color-secondary); font-size: 12px; }
.chart-body { display: flex; gap: 10px; align-items: flex-end; height: 150px; overflow-x: auto; }
.chart-group { display: flex; flex-direction: column; align-items: center; min-width: 46px; height: 100%; }
.chart-bars { display: flex; gap: 3px; align-items: flex-end; flex: 1; }
.chart-bar { width: 14px; min-height: 2px; border-radius: 3px 3px 0 0; background: var(--el-color-primary); }
.chart-bar:nth-child(2) { background: var(--el-color-success); }
.chart-bar:nth-child(3) { background: var(--el-color-warning); }
.chart-label { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 6px; max-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chart-legend { display: flex; gap: 12px; margin-top: 8px; font-size: 11px; color: var(--el-text-color-secondary); }
.legend-dot { display: inline-block; width: 8px; height: 8px; border-radius: 2px; background: var(--el-color-primary); margin-right: 4px; }
</style>
