<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">角色权限</h2>
        <p class="page-desc">
          先选角色，再按模块勾选权限。权限点由各模块在 jar 里声明、启动时同步进数据库（<code>sys_permission</code>），
          页面上显示中文名称，编码只作辅助；新增模块或补权限点走「权限点管理」。
        </p>
      </div>
      <div class="page-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建角色</el-button>
      </div>
    </div>

    <el-row :gutter="18">
      <el-col :span="8">
        <div class="card">
          <h3 class="card-title"><span class="dot"></span>角色列表</h3>
          <div class="role-list">
            <div v-for="role in roles" :key="role.id"
                 class="role-item" :class="{ active: current?.id === role.id }"
                 @click="selectRole(role)">
              <div class="name">
                {{ role.roleName }}
                <el-tag v-if="role.builtIn" size="small" type="warning" effect="plain">内置</el-tag>
              </div>
              <div class="meta">
                <span class="code-chip">{{ role.roleCode }}</span>
                <span style="margin-left: 8px">{{ scopeLabel(role.dataScope) }}</span>
              </div>
              <div class="meta">{{ role.permissions?.length || 0 }} 个权限点</div>
            </div>
            <el-empty v-if="!roles.length" description="还没有角色" :image-size="70" />
          </div>
        </div>
      </el-col>

      <el-col :span="16">
        <div class="card">
          <div class="toolbar">
            <div>
              <h3 class="card-title" style="margin: 0">
                <span class="dot"></span>{{ current ? current.roleName : '权限配置' }}
              </h3>
              <div class="hint" v-if="current">
                {{ current.roleCode }} · {{ scopeLabel(current.dataScope) }}
              </div>
            </div>
            <div class="spacer"></div>
            <el-input v-model="keyword" placeholder="搜索权限名称" clearable style="width: 210px" :prefix-icon="Search" />
            <el-button @click="openCatalog">权限点管理</el-button>
            <el-button @click="toggleAll">{{ allChecked ? '清空全部' : '全选全部' }}</el-button>
            <el-button type="primary" :disabled="!current" @click="save">保存权限</el-button>
          </div>

          <div v-if="!current" class="empty">
            <div class="big">◎</div>
            左侧选择一个角色，开始配置权限
          </div>

          <div v-else>
            <div v-for="group in filteredGroups" :key="group.module" class="perm-group">
              <div class="perm-group-head">
                <div>
                  <b>{{ group.label }}</b>
                  <span class="hint" style="margin-left: 8px">{{ group.permissions.length }} 项</span>
                </div>
                <el-checkbox :model-value="groupChecked(group)" @change="toggleGroup(group, $event)">
                  全选本组
                </el-checkbox>
              </div>
              <div class="perm-items">
                <el-checkbox v-for="permission in group.permissions" :key="permission.code"
                             :model-value="selected.includes(permission.code)"
                             @change="togglePermission(permission.code, $event)" class="perm-item">
                  <div>
                    <div class="label">
                      {{ permission.label }}
                      <el-tag size="small" effect="plain" :type="permission.type === 'MENU' ? 'success' : 'info'"
                              style="margin-left: 6px">
                        {{ permission.type === 'MENU' ? '菜单' : '按钮' }}
                      </el-tag>
                    </div>
                    <div class="code">{{ permission.code }}</div>
                  </div>
                </el-checkbox>
              </div>
            </div>
            <el-empty v-if="!filteredGroups.length" description="没有匹配的权限点" :image-size="70" />
          </div>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="createOpen" title="新建角色" width="480px">
      <el-form label-width="92px">
        <el-form-item label="角色名称"><el-input v-model="form.roleName" placeholder="如 订单管理员" /></el-form-item>
        <el-form-item label="角色编码"><el-input v-model="form.roleCode" placeholder="如 ORDER_MANAGER" /></el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option label="全部数据" value="ALL" />
            <el-option label="本组织及下级" value="ORG_AND_CHILDREN" />
            <el-option label="本组织" value="ORG" />
            <el-option label="仅本人" value="SELF" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <!-- 权限点管理：目录落库后才能在这里改，模块清单只负责声明初始值 -->
    <el-drawer v-model="catalogOpen" size="900px" title="权限点管理">
      <el-alert type="info" :closable="false" show-icon
                title="模块在自己的 jar 里声明权限点，启动时自动同步到这张目录；这里可以补自定义权限点、改中文名、停用废弃项。"
                description="模块声明的权限点不能删除（下次启动会同步回来），需要退役请改为停用。" />
      <el-tabs v-model="catalogTab" style="margin-top: 14px">
        <el-tab-pane label="权限点" name="permissions">
          <div class="toolbar">
            <el-input v-model="catalogKeyword" placeholder="筛选名称或编码" clearable
                      style="width: 220px" :prefix-icon="Search" />
            <span class="hint">共 {{ manageRows.length }} 个，自定义 {{ customCount }} 个；停用的不参与角色勾选</span>
            <div class="spacer"></div>
            <el-button type="primary" :icon="Plus" @click="addOpen = true">新增权限点</el-button>
          </div>
          <el-table :data="filteredManage" size="small" max-height="470">
            <el-table-column prop="moduleLabel" label="模块" width="120" show-overflow-tooltip />
            <el-table-column label="中文名" width="210">
              <template #default="{ row }">
                <el-input v-model="row.label" size="small" @change="updatePermission(row)" />
              </template>
            </el-table-column>
            <el-table-column label="编码" width="180">
              <template #default="{ row }"><span class="code-chip">{{ row.code }}</span></template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small" @change="updatePermission(row)">
                  <el-option label="菜单" value="MENU" />
                  <el-option label="按钮" value="BUTTON" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="110">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.source === 'CUSTOM' ? 'warning' : 'info'">
                  {{ row.source === 'CUSTOM' ? '自定义' : '模块声明' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-switch :model-value="row.enabled" size="small" @change="toggleEnabled(row, $event)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button v-if="row.source === 'CUSTOM'" link type="danger"
                           @click="removePermission(row)">删除</el-button>
                <span v-else class="hint">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="模块名称" name="modules">
          <p class="hint">
            模块中文名默认取清单里的 <span class="mono">display-name</span>；在这里改过的名字会记住，不会被下次启动覆盖。
          </p>
          <el-table :data="modules" size="small">
            <el-table-column label="模块 ID" width="180">
              <template #default="{ row }"><span class="code-chip">{{ row.moduleId }}</span></template>
            </el-table-column>
            <el-table-column label="中文名" width="260">
              <template #default="{ row }">
                <el-input v-model="row.label" size="small" @change="renameModule(row)" />
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="120" />
            <el-table-column label="是否改过">
              <template #default="{ row }">
                <el-tag v-if="row.labelEdited" size="small" type="warning" effect="plain">已自定义</el-tag>
                <span v-else class="hint">来自清单</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <el-dialog v-model="addOpen" title="新增权限点" width="460px">
      <el-form label-width="88px">
        <el-form-item label="所属模块">
          <el-select v-model="newPerm.moduleId" placeholder="选择模块" style="width: 100%">
            <el-option v-for="item in modules" :key="item.moduleId"
                       :label="`${item.label}（${item.moduleId}）`" :value="item.moduleId" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限编码">
          <el-input v-model="newPerm.code" placeholder="如 order:export，建议 模块:动作" />
        </el-form-item>
        <el-form-item label="中文名称">
          <el-input v-model="newPerm.label" placeholder="如 导出订单" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="newPerm.type">
            <el-radio value="MENU">菜单</el-radio>
            <el-radio value="BUTTON">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" @click="createPermission">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import http, { errorText } from '../api/client'

const roles = ref<any[]>([])
const groups = ref<any[]>([])
const current = ref<any>(null)
const selected = ref<string[]>([])
const keyword = ref('')
const createOpen = ref(false)
const form = reactive({ roleCode: '', roleName: '', dataScope: 'SELF' })

const SCOPES: Record<string, string> = {
  ALL: '全部数据', ORG_AND_CHILDREN: '本组织及下级', ORG: '本组织', SELF: '仅本人', CUSTOM: '自定义'
}
function scopeLabel(value: string) {
  return SCOPES[value] || value
}

const filteredGroups = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return groups.value
  return groups.value
    .map((group) => ({
      ...group,
      permissions: group.permissions.filter((p: any) =>
        p.label.toLowerCase().includes(key) || p.code.toLowerCase().includes(key))
    }))
    .filter((group) => group.permissions.length > 0)
})

const allCodes = computed(() => groups.value.flatMap((g) => g.permissions.map((p: any) => p.code)))
const allChecked = computed(() => allCodes.value.length > 0 && allCodes.value.every((code) => selected.value.includes(code)))

function groupChecked(group: any) {
  return group.permissions.every((p: any) => selected.value.includes(p.code))
}

function togglePermission(code: string, checked: boolean) {
  selected.value = checked
    ? Array.from(new Set([...selected.value, code]))
    : selected.value.filter((item) => item !== code)
}

function toggleGroup(group: any, checked: boolean) {
  const codes = group.permissions.map((p: any) => p.code)
  selected.value = checked
    ? Array.from(new Set([...selected.value, ...codes]))
    : selected.value.filter((code) => !codes.includes(code))
}

function toggleAll() {
  selected.value = allChecked.value ? [] : [...allCodes.value]
}

function selectRole(role: any) {
  current.value = role
  selected.value = [...(role.permissions || [])]
}

async function load() {
  try {
    const [roleRes, permissionRes] = await Promise.all([
      http.get('/api/identity/roles'),
      http.get('/api/identity/permissions')
    ])
    roles.value = roleRes.data
    groups.value = permissionRes.data.groups || []
    if (roles.value.length) {
      const matched = roles.value.find((role) => role.id === current.value?.id) || roles.value[0]
      selectRole(matched)
    }
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function openCreate() {
  Object.assign(form, { roleCode: '', roleName: '', dataScope: 'SELF' })
  createOpen.value = true
}

async function create() {
  if (!form.roleCode || !form.roleName) {
    ElMessage.warning('请填写角色名称与编码')
    return
  }
  try {
    await http.post('/api/identity/roles', form)
    ElMessage.success('已创建')
    createOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function save() {
  try {
    await http.put(`/api/identity/roles/${current.value.id}/permissions`, selected.value)
    ElMessage.success('权限已保存；相关用户需重新登录生效')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

/* ---------- 权限点管理（目录落库后由页面维护） ---------- */
const catalogOpen = ref(false)
const catalogTab = ref('permissions')
const addOpen = ref(false)
const manageRows = ref<any[]>([])
const modules = ref<any[]>([])
const catalogKeyword = ref('')
const newPerm = reactive({ moduleId: '', code: '', label: '', type: 'BUTTON' })

const customCount = computed(() => manageRows.value.filter((row) => row.source === 'CUSTOM').length)

const filteredManage = computed(() => {
  const key = catalogKeyword.value.trim().toLowerCase()
  if (!key) return manageRows.value
  return manageRows.value.filter((row) =>
    (row.label || '').toLowerCase().includes(key) || (row.code || '').toLowerCase().includes(key))
})

async function refreshCatalog() {
  const { data } = await http.get('/api/identity/permissions')
  manageRows.value = data.manage || []
  modules.value = data.modules || []
  groups.value = data.groups || []
  // 已勾选的权限点可能被停用，保持角色状态不丢
  if (current.value) selected.value = [...(current.value.permissions || [])]
}

async function openCatalog() {
  catalogOpen.value = true
  catalogTab.value = 'permissions'
  try {
    await refreshCatalog()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function createPermission() {
  if (!newPerm.moduleId || !newPerm.code || !newPerm.label) {
    ElMessage.warning('请选择模块并填写编码与中文名')
    return
  }
  try {
    await http.post('/api/identity/permissions', newPerm)
    ElMessage.success('已新增；在角色里勾选后保存即可分配')
    addOpen.value = false
    Object.assign(newPerm, { moduleId: '', code: '', label: '', type: 'BUTTON' })
    await refreshCatalog()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function updatePermission(row: any) {
  if (!row.label) {
    ElMessage.warning('中文名不能为空')
    await refreshCatalog()
    return
  }
  try {
    await http.put(`/api/identity/permissions/${encodeURIComponent(row.code)}`,
      { label: row.label, type: row.type })
    ElMessage.success(`已更新 ${row.code}`)
    await refreshCatalog()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function toggleEnabled(row: any, value: boolean) {
  try {
    await http.post(`/api/identity/permissions/${encodeURIComponent(row.code)}/enabled`,
      null, { params: { value } })
    await refreshCatalog()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function removePermission(row: any) {
  try {
    await ElMessageBox.confirm(`删除自定义权限点 ${row.code}？已分配该权限的角色会失去它。`, '删除权限点',
      { type: 'warning' })
    await http.delete(`/api/identity/permissions/${encodeURIComponent(row.code)}`)
    ElMessage.success('已删除')
    await refreshCatalog()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function renameModule(row: any) {
  if (!row.label) {
    ElMessage.warning('模块名称不能为空')
    await refreshCatalog()
    return
  }
  try {
    await http.put(`/api/identity/modules/${encodeURIComponent(row.moduleId)}`, { label: row.label })
    ElMessage.success('模块名称已更新')
    await refreshCatalog()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(load)
</script>
