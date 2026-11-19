<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">流程表单</h2>
        <p class="page-desc">
          表单按编码管理，编辑生成新版本草稿；作废只挡新绑定，已经被流程节点引用的表单仍按原版本渲染。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="createForm">新增表单</el-button>
      </div>
    </div>

    <div class="card" style="margin-bottom: 14px">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索表单名称或编码" clearable
                  :prefix-icon="Search" style="width: 280px" />
        <span class="hint">共 {{ filtered.length }} 个表单</span>
      </div>
    </div>

    <el-table :data="filtered" size="small" v-loading="loading">
      <el-table-column label="名称" min-width="220">
        <template #default="{ row }">
          <div class="name-cell">
            <b>{{ row.name || row.code }}</b>
            <el-tag v-if="row.suspended" size="small" type="danger" effect="plain">已作废</el-tag>
          </div>
          <div class="muted">{{ row.code }}</div>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="90">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row)" effect="plain">{{ statusText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" size="small" @click="editForm(row)">编辑</el-button>
          <el-button link :type="row.suspended ? 'success' : 'danger'" size="small"
                     @click="toggleSuspend(row)">
            {{ row.suspended ? '恢复启用' : '作废' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !filtered.length" description="还没有表单，点右上角新增" :image-size="80" />

    <el-dialog v-model="detailOpen" title="表单详情" width="760px">
      <div v-if="detailRow" class="detail">
        <div class="detail-meta">
          <span><b>{{ detailRow.name || detailRow.code }}</b></span>
          <span class="code-chip">{{ detailRow.code }}</span>
          <span>v{{ detailRow.version }}</span>
          <el-tag size="small" :type="statusTag(detailRow)" effect="plain">
            {{ statusText(detailRow) }}
          </el-tag>
        </div>
        <div class="detail-rule">
          <FormRenderer :rule="detailRow.uiSchemaJson || '[]'" readonly />
        </div>
      </div>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-button type="primary" @click="detailRow && editForm(detailRow)">编辑此表单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'
import FormRenderer from './FormRenderer.vue'

const router = useRouter()
const forms = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const detailOpen = ref(false)
const detailRow = ref<any>(null)

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return forms.value
  return forms.value.filter((row) =>
    `${row.name || ''} ${row.code || ''}`.toLowerCase().includes(text))
})

function statusText(row: any) {
  if (row.suspended) return '已作废'
  return ({ PUBLISHED: '已发布', DRAFT: '草稿', ARCHIVED: '已归档' } as Record<string, string>)[row.status] || row.status
}

function statusTag(row: any): 'danger' | 'success' | 'info' | 'warning' {
  if (row.suspended) return 'danger'
  if (row.status === 'PUBLISHED') return 'success'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/metadata/FORM')
    forms.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function createForm() {
  router.push({ path: '/workflow/forms/design' })
}

function editForm(row: any) {
  detailOpen.value = false
  router.push({ path: '/workflow/forms/design', query: { code: row.code } })
}

function openDetail(row: any) {
  detailRow.value = row
  detailOpen.value = true
}

async function toggleSuspend(row: any) {
  const suspending = !row.suspended
  try {
    await ElMessageBox.confirm(
      suspending
        ? `确认作废表单「${row.name || row.code}」？已绑定该表单的流程节点仍按原版本渲染，作废只影响后续新绑定。`
        : `确认恢复表单「${row.name || row.code}」？恢复后可以继续被流程节点绑定。`,
      suspending ? '作废确认' : '恢复确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.post(`/api/metadata/FORM/${row.code}/${suspending ? 'suspend' : 'activate'}`)
    ElMessage.success(suspending ? '已作废' : '已恢复')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(load)
</script>

<style scoped>
.name-cell { display: flex; align-items: center; gap: 8px; }
.muted { color: var(--bp-muted); font-size: 11px; }
.detail-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.detail-rule { border: 1px solid var(--bp-line); border-radius: 10px; padding: 16px; background: #FFF; }
</style>
