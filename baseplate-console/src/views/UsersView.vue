<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-desc">
          管理员或高级角色可以创建下级账号、分配角色、重置密码。数据范围由角色决定，列表会自动收窄。
        </p>
      </div>
      <div class="page-actions">
        <el-button type="primary" :icon="Plus" v-if="auth.has('user:create')" @click="openCreate">新建用户</el-button>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索用户名 / 显示名" clearable style="width: 260px"
                  :prefix-icon="Search" @keyup.enter="load" />
        <el-button @click="load">查询</el-button>
        <span class="hint">共 {{ total }} 人</span>
        <div class="spacer"></div>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" row-key="id">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 10px">
              <div class="avatar">{{ (row.displayName || row.username).slice(0, 1).toUpperCase() }}</div>
              <div>
                <div style="font-weight: 600">{{ row.displayName || row.username }}</div>
                <div class="hint">{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="组织" width="150">
          <template #default="{ row }">{{ orgName(row.orgId) }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="220">
          <template #default="{ row }">
            <el-tag v-for="role in row.roles" :key="role" size="small" effect="plain" style="margin: 2px 4px 2px 0">
              {{ roleName(role) }}
            </el-tag>
            <span v-if="!row.roles?.length" class="hint">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="账号状态" width="230">
          <template #default="{ row }">
            <div class="user-state">
              <div>
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
                </el-tag>
                <el-tag v-if="row.mustChangePassword" type="warning" size="small" style="margin-left: 4px">待改密</el-tag>
                <el-tag v-if="isLocked(row)" type="danger" size="small" style="margin-left: 4px">已锁定</el-tag>
              </div>
              <div v-if="isLocked(row)" class="hint">锁定至 {{ fmt(row.lockedUntil) }} · 连续失败 {{ row.failedAttempts }} 次</div>
              <div v-else-if="row.failedAttempts" class="hint">连续失败 {{ row.failedAttempts }} 次</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="350" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="warning" plain @click="resetPassword(row)">重置密码</el-button>
            <el-button v-if="isLocked(row) || row.failedAttempts" size="small" type="danger" plain
                       @click="unlock(row)">解锁</el-button>
            <el-button size="small" type="danger" plain @click="disable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination layout="total, prev, pager, next" :total="total" :page-size="size"
                       :current-page="page" @current-change="onPage" />
      </div>
    </div>

    <el-dialog v-model="createOpen" title="新建用户" width="520px">
      <el-alert type="info" :closable="false" show-icon
                title="新用户首次登录会强制修改密码；初始密码由系统生成，创建后通过“重置密码”获取。" />
      <el-form label-width="92px" style="margin-top: 16px">
        <el-form-item label="用户名"><el-input v-model="createForm.username" placeholder="3-32 位字母数字" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="createForm.displayName" /></el-form-item>
        <el-form-item label="组织">
          <el-select v-model="createForm.orgId" clearable style="width: 100%">
            <el-option v-for="org in orgs" :key="org.id" :label="org.name" :value="org.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createForm.roleIds" multiple style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editOpen" title="编辑用户" width="520px">
      <el-form label-width="92px">
        <el-form-item label="显示名"><el-input v-model="editForm.displayName" /></el-form-item>
        <el-form-item label="组织">
          <el-select v-model="editForm.orgId" clearable style="width: 100%">
            <el-option v-for="org in orgs" :key="org.id" :label="org.name" :value="org.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editForm.status" style="width: 100%">
            <el-option label="正常" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.roleIds" multiple style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordOpen" title="重置密码" width="460px">
      <el-alert type="warning" :closable="false" show-icon
                title="该密码只显示一次。重置会清空账号锁定、强制用户首次登录改密，旧密码与旧令牌立即失效。" />
      <div class="detail-hero" style="margin-top: 14px; text-align: center">
        <div class="hint">新的初始密码</div>
        <div class="mono" style="font-size: 22px; font-weight: 700; letter-spacing: 1px; margin-top: 6px">
          {{ newPassword }}
        </div>
      </div>
      <template #footer>
        <el-button @click="passwordOpen = false">关闭</el-button>
        <el-button type="primary" @click="copyPassword">复制密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const loading = ref(false)
const roles = ref<any[]>([])
const orgs = ref<any[]>([])
const createOpen = ref(false)
const editOpen = ref(false)
const passwordOpen = ref(false)
const newPassword = ref('')
const editing = ref<any>(null)
const createForm = reactive<any>({ username: '', displayName: '', orgId: null, roleIds: [] })
const editForm = reactive<any>({ displayName: '', orgId: null, status: 'ACTIVE', roleIds: [] })

function roleName(code: string) {
  return roles.value.find((role) => role.roleCode === code)?.roleName || code
}
function orgName(id: number | null) {
  return orgs.value.find((org) => org.id === id)?.name || '—'
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/identity/users', {
      params: { keyword: keyword.value, page: page.value, size }
    })
    rows.value = data.items
    total.value = data.total
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

async function loadMeta() {
  const [roleRes, orgRes] = await Promise.allSettled([
    http.get('/api/identity/roles'),
    http.get('/api/identity/orgs')
  ])
  if (roleRes.status === 'fulfilled') roles.value = roleRes.value.data
  if (orgRes.status === 'fulfilled') orgs.value = orgRes.value.data
}

function onPage(value: number) {
  page.value = value
  load()
}

function openCreate() {
  Object.assign(createForm, { username: '', displayName: '', orgId: null, roleIds: [] })
  createOpen.value = true
}

async function submitCreate() {
  try {
    await http.post('/api/identity/users', createForm)
    ElMessage.success('已创建；请通过“重置密码”获取初始密码')
    createOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function openEdit(row: any) {
  editing.value = row
  Object.assign(editForm, {
    displayName: row.displayName,
    orgId: row.orgId,
    status: row.status,
    roleIds: roles.value.filter((role) => row.roles.includes(role.roleCode)).map((role) => role.id)
  })
  editOpen.value = true
}

async function submitEdit() {
  try {
    await http.put(`/api/identity/users/${editing.value.id}`, editForm)
    ElMessage.success('已保存')
    editOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function isLocked(row: any) {
  return !!row.lockedUntil && new Date(row.lockedUntil).getTime() > Date.now()
}
function fmt(value: any) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function unlock(row: any) {
  try {
    await ElMessageBox.confirm(
      `解锁 ${row.displayName || row.username}？只清除登录失败次数与锁定时点，不修改密码。`,
      '解锁账号', { type: 'warning' })
    await http.post(`/api/identity/users/${row.id}/unlock`, {})
    ElMessage.success('已解锁，用户现在可以用正确密码登录')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function resetPassword(row: any) {
  try {
    await ElMessageBox.confirm(
      `重置 ${row.displayName || row.username} 的密码？会换成一次性随机密码并强制首登改密，同时解除账号锁定；旧令牌立即失效。`,
      '重置密码', { type: 'warning' })
    const { data } = await http.post(`/api/identity/users/${row.id}/reset-password`, {})
    newPassword.value = data.password
    passwordOpen.value = true
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function copyPassword() {
  try {
    await navigator.clipboard.writeText(newPassword.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中复制')
  }
}

async function disable(row: any) {
  try {
    await ElMessageBox.confirm(`确认停用 ${row.displayName || row.username}？`, '停用用户', { type: 'warning' })
    await http.delete(`/api/identity/users/${row.id}`)
    ElMessage.success('已停用')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

onMounted(async () => {
  await loadMeta()
  await load()
})
</script>

<style scoped>
.user-state > div + div { margin-top: 4px; }
.user-state .hint { font-size: 12px; color: #8B5A3C; }
</style>
