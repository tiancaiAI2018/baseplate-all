<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">C 端页面</h2>
        <p class="page-desc">拖拽区块搭建活动页、报名页；支持 AI 生成、主题配色与入场动画，发布后可用公开链接访问。</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="createDialog = true">新建页面</el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="pages" v-loading="loading" size="small">
        <el-table-column prop="code" label="编码" width="180" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="design(row)">设计</el-button>
            <el-button link type="primary" @click="publish(row)" :disabled="row.status === 'PUBLISHED'">发布</el-button>
            <el-button link @click="preview(row)" :disabled="row.status !== 'PUBLISHED'">公开预览</el-button>
            <el-button link @click="copyLink(row)" :disabled="row.status !== 'PUBLISHED'">复制链接</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!pages.length && !loading" description="还没有 C 端页面" :image-size="64" />
    </div>

    <el-dialog v-model="createDialog" title="新建 C 端页面" width="440px">
      <el-form label-width="90px">
        <el-form-item label="页面编码">
          <el-input v-model="newCode" placeholder="如 signup_page" />
        </el-form-item>
        <el-form-item label="页面名称">
          <el-input v-model="newName" placeholder="如 活动报名页" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="create">创建并设计</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { errorText } from '../api/client'
import { NodeType, type MApp } from '@tmagic/core'

function emptyDsl(): MApp {
  return {
    id: 'cend_app', name: 'C端页面', type: NodeType.ROOT,
    items: [{
      id: 'page_home', name: 'index', type: NodeType.PAGE, layout: 'absolute',
      style: { position: 'relative', width: '100%', height: '100%', backgroundColor: '#ffffff' },
      items: []
    }]
  }
}

const router = useRouter()
const pages = ref<any[]>([])
const loading = ref(false)
const createDialog = ref(false)
const newCode = ref('')
const newName = ref('')

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/metadata/CEND_PAGE')
    pages.value = data || []
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

async function create() {
  const code = newCode.value.trim()
  if (!/^[a-z][a-z0-9_]*$/.test(code)) {
    ElMessage.warning('编码必须以小写字母开头，只能包含小写字母、数字和下划线')
    return
  }
  const dsl = emptyDsl()
  try {
    await http.post(`/api/metadata/CEND_PAGE/${code}`, {
      name: newName.value || code,
      schema: { fields: [] },
      uiSchema: { version: 2, dsl }
    })
    createDialog.value = false
    await router.push({ path: '/cend/pages/design', query: { code } })
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function design(row: any) {
  void router.push({ path: '/cend/pages/design', query: { code: row.code } })
}

async function publish(row: any) {
  try {
    await ElMessageBox.confirm(`确认发布页面 ${row.name || row.code}？发布后公开链接即可访问。`, '提示', { type: 'warning' })
    await http.post(`/api/metadata/CEND_PAGE/${row.code}/publish`, null, { params: { version: row.version } })
    ElMessage.success('已发布')
    await load()
  } catch (error: any) {
    if (error !== 'cancel' && error?.toString() !== 'cancel') ElMessage.error(errorText(error))
  }
}

function preview(row: any) {
  window.open(`#/c/${row.code}`, '_blank')
}

async function copyLink(row: any) {
  const link = `${location.origin}${location.pathname}#/c/${row.code}`
  try {
    await navigator.clipboard.writeText(link)
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.info(link)
  }
}

onMounted(load)
</script>
