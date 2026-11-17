<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">概览</h2>
        <p class="page-desc">基盘运行时状态、已启用模块与最近动态。数字来自真实接口，刷新即最新。</p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="$router.push('/platform/apps')">创建开放应用</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat">
        <div class="icon green"><el-icon><User /></el-icon></div>
        <div><div class="num">{{ stats.users }}</div><div class="label">系统用户</div></div>
      </div>
      <div class="stat">
        <div class="icon orange"><el-icon><Connection /></el-icon></div>
        <div><div class="num">{{ stats.apps }}</div><div class="label">开放应用</div></div>
      </div>
      <div class="stat">
        <div class="icon blue"><el-icon><Document /></el-icon></div>
        <div><div class="num">{{ stats.audits }}</div><div class="label">审计记录</div></div>
      </div>
      <div class="stat">
        <div class="icon purple"><el-icon><Share /></el-icon></div>
        <div>
          <div class="num">{{ stats.flows }}</div>
          <div class="label">流程定义<span v-if="stats.running !== '-'" class="label-sub"> · {{ stats.running }} 运行中</span></div>
        </div>
      </div>
    </div>

    <el-row :gutter="18" style="margin-top: 18px">
      <el-col :span="14">
        <div class="card">
          <h3 class="card-title"><span class="dot"></span>最近访问审计</h3>
          <el-table :data="recent" size="small" v-loading="loading">
            <el-table-column prop="created_at" label="时间" width="170">
              <template #default="{ row }">{{ fmt(row.created_at) }}</template>
            </el-table-column>
            <el-table-column prop="actor" label="操作人" width="110" />
            <el-table-column prop="action" label="方法" width="80" />
            <el-table-column prop="resource" label="路径" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="Number(row.result_code) < 400 ? 'success' : 'danger'" size="small">
                  {{ row.result_code }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="card">
          <h3 class="card-title"><span class="dot"></span>已启用模块</h3>
          <div v-for="module in modules" :key="module.id" class="module-row">
            <div>
              <div style="font-size: 13.5px; font-weight: 600">{{ moduleLabel(module.id) }}</div>
              <div class="hint">{{ module.id }} · v{{ module.version }}</div>
            </div>
            <el-tag size="small" type="success" effect="plain">运行中</el-tag>
          </div>
          <el-empty v-if="!modules.length" description="模块清单为空" :image-size="70" />
        </div>
        <div class="card">
          <h3 class="card-title"><span class="dot"></span>快捷入口</h3>
          <div class="quick">
            <div @click="$router.push('/identity/users')"><el-icon><User /></el-icon>用户管理</div>
            <div @click="$router.push('/identity/roles')"><el-icon><Key /></el-icon>角色权限</div>
            <div @click="$router.push('/audit/logs')"><el-icon><Document /></el-icon>审计日志</div>
            <div @click="$router.push('/workflow/designer')"><el-icon><Share /></el-icon>工作流</div>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Connection, Document, Share, Refresh, Plus } from '@element-plus/icons-vue'
import http from '../api/client'

const stats = ref({ users: '-', apps: '-', audits: '-', flows: '-', running: '-' })
const recent = ref<any[]>([])
const modules = ref<any[]>([])
const loading = ref(false)

const LABELS: Record<string, string> = {
  identity: '身份与权限', platform: '开放平台', audit: '审计日志',
  workflow: '工作流', rule: '规则引擎', metadata: '低代码配置'
}
function moduleLabel(id: string) {
  return LABELS[id] || id
}
function fmt(value: string) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}

// 每个数据块独立加载、到货即渲染。
// 以前是 Promise.allSettled 后统一赋值：只要有一个接口慢（例如工作流定义曾经要 4~5 秒），
// 全页数字就一起空着，看起来像"页面没渲染"。
const failures: string[] = []

async function pull(label: string, task: () => Promise<void>) {
  try {
    await task()
  } catch (error) {
    failures.push(label)
    console.warn('[dashboard] ' + label + ' 加载失败', error)
  }
}

async function load() {
  failures.length = 0
  loading.value = true
  await Promise.all([
    pull('用户数', async () => {
      const { data } = await http.get('/api/identity/users', { params: { page: 1, size: 1 } })
      stats.value.users = data.total
    }),
    pull('开放应用', async () => {
      const { data } = await http.get('/api/platform/apps')
      stats.value.apps = data.length
    }),
    pull('审计记录', async () => {
      const { data } = await http.get('/api/audit/access', { params: { page: 1, size: 8 } })
      stats.value.audits = data.total
      recent.value = data.items
    }),
    // 只要数字：走 /overview（一条 SQL），不再为了一个数去拉全量定义明细
    pull('流程概览', async () => {
      const { data } = await http.get('/api/workflow/overview')
      stats.value.flows = data.definitions
      stats.value.running = data.runningInstances
    }),
    pull('模块清单', async () => {
      const { data } = await http.get('/api/identity/permissions')
      modules.value = data.modules || []
    })
  ])
  loading.value = false
  if (failures.length) {
    ElMessage.warning('这些数据没取到：' + failures.join('、') + '（其余部分照常显示）')
  }
}

onMounted(load)
</script>

<style scoped>
.module-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 2px; border-bottom: 1px dashed var(--bp-line);
}
.module-row:last-of-type { border-bottom: none; }
.label-sub { color: #8A948F; }
.quick { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.quick > div {
  display: flex; align-items: center; gap: 8px; padding: 12px 14px; border-radius: 10px;
  background: #F6F9F7; border: 1px solid var(--bp-line); cursor: pointer; font-size: 13px;
}
.quick > div:hover { border-color: #BBD3CA; background: #F0F7F3; }
</style>
