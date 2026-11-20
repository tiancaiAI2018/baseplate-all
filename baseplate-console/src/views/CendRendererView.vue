<template>
  <div class="cend-public">
    <div v-if="loading" class="cend-status">页面加载中…</div>
    <div v-else-if="error" class="cend-status">
      <p>{{ error }}</p>
      <el-button @click="load">重试</el-button>
    </div>
    <PageRenderer v-else :dsl="dsl" :page-code="code" :submission-enabled="true" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/client'
import PageRenderer from '../components/cend/PageRenderer.vue'
import { emptyDsl, normalizeAiPage } from '../components/cend/cendDsl'
import type { CendPageDsl } from '../types/cend'

const route = useRoute()
const code = String(route.params.code || '')
const tenant = String(route.query.tenant || 'default')
const dsl = ref<CendPageDsl>(emptyDsl())
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await http.get(`/api/c/pages/${code}`, { params: { tenant } })
    dsl.value = normalizeAiPage(data.uiSchema?.blocks ? data.uiSchema : { blocks: [] })
  } catch (e: any) {
    error.value = e?.response?.data?.detail || '页面不存在或尚未发布'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.cend-public { min-height: 100vh; padding: 24px 12px; background: #f3f5f4; }
.cend-status { text-align: center; padding: 80px 0; color: #64748b; }
</style>
