<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">开放接口目录</h2>
        <p class="page-desc">
          目录主体由业务代码里的 <code>@OpenApi</code> 注解扫描生成，启动时自动同步。
          这里可以改展示名称、停用条目，也可以手工补登不在本进程内的接口；
          方法、路径、主体类型、所需权限属于安全边界，页面只读。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openRegister">手工登记</el-button>
      </div>
    </div>

    <div class="card">
      <div class="catalog-summary">
        <div><b>{{ items.length }}</b><span>接口总数</span></div>
        <div><b>{{ userSubjectCount }}</b><span>需用户身份</span></div>
        <div><b>{{ manualCount }}</b><span>手工登记</span></div>
        <div><b>{{ disabledCount }}</b><span>已停用</span></div>
      </div>

      <div class="toolbar" style="margin-top: 4px">
        <el-select v-model="filterGroup" placeholder="全部分组" clearable style="width: 150px">
          <el-option v-for="group in groups" :key="group" :label="group" :value="group" />
        </el-select>
        <el-select v-model="filterSubject" placeholder="全部主体" clearable style="width: 145px">
          <el-option label="机器身份" value="CLIENT" />
          <el-option label="用户身份" value="USER" />
          <el-option label="两者皆可" value="ANY" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索名称 / 编码 / 路径" clearable
                  style="width: 220px" :prefix-icon="Search" />
        <div class="spacer"></div>
        <span class="hint">{{ filtered.length }} / {{ items.length }} 条</span>
      </div>

      <el-table :data="filtered" size="small" v-loading="loading">
        <el-table-column label="接口" min-width="250">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 6px">
              <span style="font-weight: 600">{{ row.name }}</span>
              <el-tag v-if="row.labelEdited" size="small" type="info" effect="plain">已改名</el-tag>
            </div>
            <div class="mono hint">{{ row.code }}</div>
            <div class="hint" v-if="row.description">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column label="方法与路径" min-width="210">
          <template #default="{ row }">
            <span class="method" :class="row.method">{{ row.method }}</span>
            <span class="mono" style="margin-left: 6px">{{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="调用主体" width="126">
          <template #default="{ row }">
            <el-tag size="small" :type="subjectTag(row.subject)" effect="plain">
              {{ subjectLabel(row.subject) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户需具备权限" width="170">
          <template #default="{ row }">
            <span v-if="row.requiredPermission" class="code-chip">{{ row.requiredPermission }}</span>
            <span v-else class="hint">{{ row.subject === 'USER' ? '不校验' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="118">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'MANUAL' ? 'warning' : 'info'" effect="plain">
              {{ row.source === 'MANUAL' ? '手工' : '注解' }}
            </el-tag>
            <div class="hint" style="margin-top: 3px" v-if="row.groupName">{{ row.groupName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(value: boolean) => toggle(row, value)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRelabel(row)">改名</el-button>
            <el-button v-if="row.source === 'MANUAL'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="hint" style="margin-top: 10px; line-height: 1.8">
        停用后该接口立即从鉴权目录消失，返回 <b>404 API_NOT_FOUND</b>；已授权的应用需要重新授权。
        注解声明的条目不能删除（下次启动会回来），请用停用。
      </p>
    </div>

    <el-dialog v-model="registerOpen" title="手工登记开放接口" width="580px">
      <el-alert type="info" :closable="false" show-icon
                title="仅用于接口不在本进程内的情况（走网关转发的既有系统）。本工程内的接口请用 @OpenApi 注解。"
                style="margin-bottom: 14px" />
      <el-form label-width="110px">
        <el-form-item label="接口编码">
          <el-input v-model="form.code" placeholder="如 legacy.order.query" />
        </el-form-item>
        <el-form-item label="接口名称">
          <el-input v-model="form.name" placeholder="如 查询历史订单" />
        </el-form-item>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="方法" label-width="60px">
              <el-select v-model="form.method" style="width: 100%">
                <el-option v-for="m in ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="路径" label-width="60px">
              <el-input v-model="form.path" placeholder="/openapi/v1/legacy/orders" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="分组">
          <el-input v-model="form.groupName" placeholder="不填归入「手工登记」" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="调用主体">
          <el-radio-group v-model="form.subject">
            <el-radio value="CLIENT">机器身份</el-radio>
            <el-radio value="USER">用户身份</el-radio>
            <el-radio value="ANY">两者皆可</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="用户需具备权限" v-if="form.subject !== 'CLIENT'">
          <el-input v-model="form.requiredPermission" placeholder="如 order:view，留空表示不校验" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerOpen = false">取消</el-button>
        <el-button type="primary" @click="register">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="relabelOpen" title="修改展示信息" width="480px">
      <el-form label-width="90px">
        <el-form-item label="接口编码">
          <span class="mono">{{ editing.code }}</span>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="editing.name" /></el-form-item>
        <el-form-item label="分组"><el-input v-model="editing.groupName" /></el-form-item>
        <el-form-item label="说明">
          <el-input v-model="editing.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <p class="hint">改过名的条目下次启动同步不会被代码注解覆盖。</p>
      <template #footer>
        <el-button @click="relabelOpen = false">取消</el-button>
        <el-button type="primary" @click="relabel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'

const items = ref<any[]>([])
const groups = ref<string[]>([])
const loading = ref(false)
const registerOpen = ref(false)
const relabelOpen = ref(false)
const filterGroup = ref('')
const filterSubject = ref('')
const keyword = ref('')

const form = reactive<any>({
  code: '', name: '', method: 'GET', path: '', groupName: '',
  description: '', subject: 'CLIENT', requiredPermission: ''
})
const editing = reactive<any>({ code: '', name: '', groupName: '', description: '' })

const filtered = computed(() => items.value.filter((item) => {
  if (filterGroup.value && (item.groupName || '未分组') !== filterGroup.value) return false
  if (filterSubject.value && item.subject !== filterSubject.value) return false
  if (keyword.value) {
    const kw = keyword.value.toLowerCase()
    const hit = [item.name, item.code, item.path]
      .some((value) => String(value || '').toLowerCase().includes(kw))
    if (!hit) return false
  }
  return true
}))
const userSubjectCount = computed(() => items.value.filter((item) => item.subject === 'USER').length)
const manualCount = computed(() => items.value.filter((item) => item.source === 'MANUAL').length)
const disabledCount = computed(() => items.value.filter((item) => !item.enabled).length)

const SUBJECT_LABELS: Record<string, string> = { CLIENT: '机器身份', USER: '用户身份', ANY: '两者皆可' }
function subjectLabel(subject: string) { return SUBJECT_LABELS[subject] || subject }
function subjectTag(subject: string) {
  if (subject === 'USER') return 'warning'
  if (subject === 'ANY') return 'primary'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/platform/apis')
    items.value = data.items || []
    groups.value = data.groups || []
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function openRegister() {
  Object.assign(form, {
    code: '', name: '', method: 'GET', path: '/openapi/v1/', groupName: '',
    description: '', subject: 'CLIENT', requiredPermission: ''
  })
  registerOpen.value = true
}

async function register() {
  if (!/^[A-Za-z0-9._:-]{3,64}$/.test(form.code || '')) {
    ElMessage.warning('接口编码只能包含字母数字与 . _ : -，长度 3-64')
    return
  }
  if (!form.name || !form.path) {
    ElMessage.warning('接口名称与路径不能为空')
    return
  }
  try {
    await http.post('/api/platform/apis', { ...form, sandboxAllowed: true })
    ElMessage.success('已登记')
    registerOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function openRelabel(row: any) {
  Object.assign(editing, {
    code: row.code, name: row.name,
    groupName: row.groupName || '', description: row.description || ''
  })
  relabelOpen.value = true
}

async function relabel() {
  try {
    await http.put(`/api/platform/apis/${encodeURIComponent(editing.code)}`, {
      name: editing.name, groupName: editing.groupName, description: editing.description
    })
    ElMessage.success('已保存')
    relabelOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function toggle(row: any, value: boolean) {
  try {
    await http.post(`/api/platform/apis/${encodeURIComponent(row.code)}/enabled`, null,
      { params: { value } })
    row.enabled = value
    ElMessage.success(value ? '已启用' : '已停用，鉴权时会返回 404')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`删除手工登记的接口 ${row.name}？已授权该接口的应用会失去访问。`,
      '删除接口', { type: 'warning' })
    await http.delete(`/api/platform/apis/${encodeURIComponent(row.code)}`)
    ElMessage.success('已删除')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

onMounted(load)
</script>

<style scoped>
.catalog-summary {
  display: flex;
  gap: 34px;
  padding: 16px 18px;
  margin-bottom: 12px;
  background: #F4F8F6;
  border: 1px solid #DCE7E1;
  border-radius: 12px;
}
.catalog-summary > div { display: flex; flex-direction: column; gap: 2px; }
.catalog-summary b { font-size: 22px; font-weight: 700; color: #17564A; line-height: 1.2; }
.catalog-summary span { font-size: 12px; color: #6B7671; }
</style>
