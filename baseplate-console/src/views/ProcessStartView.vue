<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">发起流程</h1>
        <p class="page-desc">选择你要办理的业务流程，填写表单后提交；审批进度可在「我发起的」里随时查看。</p>
      </div>
      <div class="page-actions">
        <el-button :loading="loading" @click="loadCatalog">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新目录
        </el-button>
      </div>
    </div>

    <div v-if="recent.length" class="card recent-card">
      <div class="card-title"><span class="dot"></span>最近发起</div>
      <div class="recent-strip">
        <div v-for="item in recent" :key="item.id" class="recent-item" @click="openInstance(item.id)">
          <div class="recent-name">{{ item.processDefinitionName || item.processDefinitionKey }}</div>
          <div class="recent-meta">
            <span class="mono">{{ item.businessKey || '—' }}</span>
            <el-tag :type="instanceTagType(item)" size="small" effect="plain">{{ instanceStatus(item) }}</el-tag>
          </div>
          <div class="recent-time">{{ fmt(item.startedAt) }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <div class="cat-chips">
          <button v-for="cat in categories" :key="cat" class="cat-chip" :class="{ active: activeCategory === cat }"
                  @click="activeCategory = cat">{{ cat }}</button>
        </div>
        <div class="spacer"></div>
        <el-input v-model="keyword" placeholder="搜索流程名称 / 说明" clearable style="width: 240px"
                  :prefix-icon="Search" />
      </div>

      <div v-loading="loading">
        <div v-if="filtered.length" class="process-grid">
          <div v-for="item in filtered" :key="item.processKey" class="process-card">
            <div class="process-top">
              <div class="process-icon" :style="iconStyle(item)">{{ item.icon || '📄' }}</div>
              <div class="process-head">
                <div class="process-name">{{ item.name }}</div>
                <div class="process-tags">
                  <el-tag size="small" effect="plain" type="info">v{{ item.version }}</el-tag>
                  <span class="pill gray">{{ item.category || '通用' }}</span>
                </div>
              </div>
            </div>
            <p class="process-desc">{{ item.description || '暂无流程说明，可直接发起体验。' }}</p>
            <div class="process-foot">
              <span class="hint">{{ item.formCode ? '需填写发起表单' : '无需填写表单' }}</span>
              <el-button type="primary" @click="openStart(item)">
                <el-icon style="margin-right: 4px"><Promotion /></el-icon>发起
              </el-button>
            </div>
          </div>
        </div>
        <div v-else-if="!loading" class="empty">
          <div class="big">🗂️</div>
          <template v-if="catalog.length">没有匹配的流程，换个关键词或分类试试</template>
          <template v-else>
            当前没有你可以发起的流程。<br />
            可能是管理员尚未部署流程，或你的账号不在流程的发起范围内，请联系管理员。
          </template>
        </div>
      </div>
    </div>

    <el-dialog v-model="startOpen" :title="`发起 · ${current?.name || ''}`" width="640px" destroy-on-close
               :close-on-click-modal="false">
      <template v-if="!startedId">
        <div class="start-hero">
          <div class="process-icon" :style="iconStyle(current)">{{ current?.icon || '📄' }}</div>
          <div>
            <b>{{ current?.name }}</b>
            <div class="hint">{{ current?.description || '填写下方表单后提交，流程将按配置的节点依次审批。' }}</div>
          </div>
        </div>
        <el-form label-position="top">
          <el-form-item label="业务单号">
            <el-input v-model="businessKey" placeholder="留空则自动生成" maxlength="64">
              <template #append>
                <el-button @click="businessKey = makeBusinessKey()">重新生成</el-button>
              </template>
            </el-input>
          </el-form-item>
        </el-form>
        <div v-if="startRules.length" class="start-form">
          <FormRenderer ref="rendererRef" :rule="current?.formUiSchema || []" />
        </div>
        <el-alert v-else type="info" :closable="false" show-icon
                  title="该流程未配置发起表单，点击提交即可发起" style="margin-top: 4px" />
      </template>
      <div v-else class="start-success">
        <div class="success-icon">✓</div>
        <h3>发起成功</h3>
        <p class="hint">流程已进入审批，办理人会收到待办；你可以随时查看进度或撤回。</p>
        <div class="mono" style="margin-bottom: 18px">{{ startedBusinessKey }}</div>
        <div style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap">
          <el-button type="primary" @click="viewStarted">查看进度</el-button>
          <el-button @click="resetStart">继续发起</el-button>
          <el-button @click="goMine">去「我发起的」</el-button>
        </div>
      </div>
      <template #footer>
        <template v-if="!startedId">
          <el-button @click="startOpen = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitStart">提交发起</el-button>
        </template>
      </template>
    </el-dialog>

    <InstanceDetailDrawer v-model="instanceOpen" :instance-id="instanceId" allow-withdraw
                          @withdrawn="loadRecent" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, Refresh, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'
import { useWorkflowStore } from '../stores/workflow'
import FormRenderer from './FormRenderer.vue'
import InstanceDetailDrawer from '../components/InstanceDetailDrawer.vue'

const router = useRouter()
const workflow = useWorkflowStore()

const loading = ref(false)
const catalog = ref<any[]>([])
const recent = ref<any[]>([])
const keyword = ref('')
const activeCategory = ref('全部')

const startOpen = ref(false)
const current = ref<any>(null)
const businessKey = ref('')
const submitting = ref(false)
const startedId = ref('')
const startedBusinessKey = ref('')
const rendererRef = ref<InstanceType<typeof FormRenderer> | null>(null)

const instanceOpen = ref(false)
const instanceId = ref('')

const categories = computed(() => ['全部', ...new Set(catalog.value.map((item) => item.category || '通用'))])
const filtered = computed(() => catalog.value.filter((item) => {
  const catOk = activeCategory.value === '全部' || (item.category || '通用') === activeCategory.value
  const text = keyword.value.trim().toLowerCase()
  const textOk = !text || `${item.name} ${item.description || ''} ${item.processKey}`.toLowerCase().includes(text)
  return catOk && textOk
}))
const startRules = computed(() => {
  const raw = current.value?.formUiSchema
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : (parsed?.rule || [])
  } catch { return [] }
})

function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }

function instanceStatus(item: any) {
  if (!item.ended) return '运行中'
  const reason = item.deleteReason || ''
  if (reason.includes('撤回')) return '已撤回'
  if (reason) return '已终止'
  return '已结束'
}
function instanceTagType(item: any) {
  const status = instanceStatus(item)
  return status === '运行中' ? 'success' : status === '已撤回' ? 'warning' : status === '已终止' ? 'danger' : 'info'
}

// 图标底色按分类取色，让目录一眼能分出行政 / 财务 / 采购等区块
const CATEGORY_HUES: Record<string, [string, string]> = {
  行政办公: ['#E8F3EF', '#CFE7DD'], 财务报销: ['#FDF0E8', '#F8DCC8'],
  人事管理: ['#EAF0F8', '#D3E1F2'], 采购管理: ['#F1ECFA', '#E0D4F2'],
  通用: ['#F1F4F2', '#E2E9E4']
}
function iconStyle(item: any) {
  const [from, to] = CATEGORY_HUES[item?.category || '通用'] || CATEGORY_HUES['通用']
  return { background: `linear-gradient(135deg, ${from}, ${to})` }
}

function makeBusinessKey() {
  const key = (current.value?.processKey || 'BIZ').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12) || 'BIZ'
  const now = new Date()
  const date = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`
  const rand = Math.random().toString(36).slice(2, 6).toUpperCase()
  return `${key}-${date}-${rand}`
}

async function loadCatalog() {
  loading.value = true
  try {
    const { data } = await http.get('/api/workflow/catalog')
    catalog.value = data || []
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { loading.value = false }
}

async function loadRecent() {
  try {
    const { data } = await http.get('/api/workflow/instances/page',
      { params: { page: 1, size: 3, mine: true } })
    recent.value = data.items || []
  } catch { recent.value = [] }
}

function openStart(item: any) {
  current.value = item
  startedId.value = ''
  businessKey.value = makeBusinessKey()
  startOpen.value = true
}

function resetStart() {
  startedId.value = ''
  businessKey.value = makeBusinessKey()
}

async function submitStart() {
  if (!current.value) return
  if (startRules.value.length && rendererRef.value?.validate) {
    try { await rendererRef.value.validate() } catch {
      ElMessage.warning('表单还有未通过校验的字段，请先补全')
      return
    }
  }
  submitting.value = true
  try {
    const formData = startRules.value.length ? (rendererRef.value?.formData?.() || {}) : {}
    const key = businessKey.value.trim() || makeBusinessKey()
    const { data } = await http.post('/api/workflow/instances', {
      processKey: current.value.processKey,
      businessKey: key,
      formCode: current.value.formCode || undefined,
      formData
    })
    startedId.value = data.processInstanceId || data.id || ''
    startedBusinessKey.value = key
    ElMessage.success('已发起')
    await Promise.allSettled([loadRecent(), workflow.refreshTodo()])
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { submitting.value = false }
}

function viewStarted() {
  startOpen.value = false
  openInstance(startedId.value)
}
function goMine() {
  startOpen.value = false
  router.push('/workflow/mine')
}
function openInstance(id: string) {
  if (!id) return
  instanceId.value = id
  instanceOpen.value = true
}

onMounted(() => { loadCatalog(); loadRecent() })
</script>

<style scoped>
.recent-card { padding: 16px 22px; }
.recent-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
.recent-item {
  border: 1px solid var(--bp-line); border-radius: 12px; padding: 12px 14px; cursor: pointer;
  transition: all .15s ease; background: #FBFCFB;
}
.recent-item:hover { border-color: #BBD3CA; transform: translateY(-1px); box-shadow: var(--bp-shadow); }
.recent-name { font-weight: 600; font-size: 13.5px; margin-bottom: 6px; }
.recent-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
.recent-time { font-size: 11.5px; color: var(--bp-muted); }

.cat-chips { display: flex; gap: 8px; flex-wrap: wrap; }
.cat-chip {
  border: 1px solid var(--bp-line); background: #fff; color: #4C5B54;
  padding: 6px 14px; border-radius: 999px; font-size: 13px; cursor: pointer; transition: all .15s ease;
}
.cat-chip:hover { border-color: #BBD3CA; color: var(--el-color-primary); }
.cat-chip.active { background: var(--el-color-primary); border-color: var(--el-color-primary); color: #fff; }

.process-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.process-card {
  border: 1px solid var(--bp-line); border-radius: 14px; padding: 18px;
  display: flex; flex-direction: column; gap: 12px; background: #fff; transition: all .18s ease;
}
.process-card:hover { border-color: #A9C8BC; transform: translateY(-2px); box-shadow: 0 12px 28px rgba(23,86,74,.10); }
.process-top { display: flex; gap: 12px; align-items: center; }
.process-icon {
  width: 48px; height: 48px; flex: 0 0 48px; border-radius: 13px;
  display: grid; place-items: center; font-size: 24px;
}
.process-head { min-width: 0; }
.process-name { font-size: 15.5px; font-weight: 700; margin-bottom: 5px; }
.process-tags { display: flex; gap: 6px; align-items: center; }
.process-desc {
  margin: 0; color: var(--bp-muted); font-size: 12.5px; line-height: 1.7; flex: 1;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 42px;
}
.process-foot { display: flex; align-items: center; justify-content: space-between; border-top: 1px dashed var(--bp-line); padding-top: 12px; }

.start-hero { display: flex; gap: 12px; align-items: center; margin-bottom: 18px; padding: 14px 16px; border-radius: 12px; background: linear-gradient(120deg, #F2F8F5, #EAF2EF); border: 1px solid #DCE9E3; }
.start-form { border: 1px solid var(--bp-line); border-radius: 12px; padding: 18px 20px 6px; background: #FBFCFB; }
.start-success { text-align: center; padding: 26px 10px 10px; }
.success-icon {
  width: 58px; height: 58px; margin: 0 auto 14px; border-radius: 50%;
  background: linear-gradient(135deg, #2E7D68, #17564A); color: #fff; font-size: 28px;
  display: grid; place-items: center; box-shadow: 0 10px 24px rgba(23,86,74,.28);
}
.start-success h3 { margin: 0 0 8px; font-size: 19px; }
.start-success .hint { margin: 0 0 10px; }
</style>