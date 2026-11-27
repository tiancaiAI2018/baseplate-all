<script setup lang="ts">
const props = defineProps<{ block: any }>()
const columns = () => (props.block?.columns || []).map(String)
const rows = () => props.block?.rows || []
</script>

<template>
  <div class="data-table-card">
    <div class="data-table-title">{{ block.title || '查询结果' }}</div>
    <div class="data-table-scroll">
      <table>
        <thead>
          <tr>
            <th v-for="column in columns()" :key="column">{{ column }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows()" :key="index">
            <td v-for="column in columns()" :key="column">{{ row[column] ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!rows().length" class="data-table-empty">没有数据</div>
  </div>
</template>

<style scoped>
.data-table-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-bg-color);
}
.data-table-title { font-weight: 600; margin-bottom: 8px; }
.data-table-scroll { overflow: auto; max-height: 240px; }
table { border-collapse: collapse; width: 100%; font-size: 12px; }
th, td { border-bottom: 1px solid var(--el-border-color-lighter); padding: 6px 8px; text-align: left; white-space: nowrap; }
th { color: var(--el-text-color-secondary); font-weight: 600; }
.data-table-empty { color: var(--el-text-color-secondary); font-size: 12px; padding-top: 6px; }
</style>
