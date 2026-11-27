<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">分单池</h1>
        <p class="page-desc">
          池是组织边界的唯一来源：只配置 A 组织时，B 组织的人既看不到也抢不到这些任务。
          默认不含下级组织，需要覆盖整个部门时再勾「含下级」。
        </p>
      </div>
      <div class="page-actions">
        <el-button plain :loading="loading" @click="loadPools">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>新建分单池
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table v-loading="loading" :data="pools">
        <el-table-column label="池" min-width="180">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <span class="code-chip" style="margin-left: 6px">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="组织范围" min-width="220">
          <template #default="{ row }">
            <template v-if="row.orgs?.length">
              <el-tag v-for="org in row.orgs.slice(0, 3)" :key="org.orgId" size="small" effect="plain"
                      style="margin: 2px 4px 2px 0">
                {{ orgName(org.orgId) }}{{ org.includeChildren ? ' + 下级' : '' }}
              </el-tag>
              <span v-if="row.orgs.length > 3" class="hint">等 {{ row.orgs.length }} 个组织</span>
            </template>
            <el-tag v-else type="danger" size="small" effect="plain">未配置组织（禁止派单）</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="允许抢单" width="100">
          <template #default="{ row }">
            <el-tag :type="row.claimEnabled ? 'success' : 'info'" size="small">
              {{ row.claimEnabled ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'" size="small" effect="dark">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'ACTIVE'" size="small" type="danger" plain
                       @click="suspend(row)">停用</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">🏢</div>
            还没有分单池，先建一个并选择组织范围
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialogOpen" :title="editingId ? '编辑分单池' : '新建分单池'" width="760px">
      <el-form label-width="96px">
        <el-form-item label="池编码">
          <el-input v-model="form.code" :disabled="!!editingId" maxlength="64" show-word-limit
                    placeholder="如 SALES_POOL，全局唯一" />
        </el-form-item>
        <el-form-item label="池名称">
          <el-input v-model="form.name" maxlength="128" placeholder="如 售前支持池" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="允许抢单">
          <el-switch v-model="form.claimEnabled" active-text="池内成员可主动认领" />
        </el-form-item>
        <el-form-item label="组织范围">
          <div class="org-picker">
            <div class="org-tree">
              <el-tree ref="treeRef" :data="orgTree" node-key="id" show-checkbox check-strictly
                       :props="{ label: 'name', children: 'children' }" default-expand-all
                       empty-text="暂无组织" @check="onTreeCheck" />
            </div>
            <div class="org-selected">
              <div v-if="!selectedOrgIds.length" class="hint" style="padding: 10px">
                左侧勾选组织；只有被勾选的组织（及其「含下级」范围）才能看到和抢这个池的任务。
              </div>
              <div v-for="orgId in selectedOrgIds" :key="orgId" class="org-selected-row">
                <span class="org-selected-name">{{ orgName(orgId) }}</span>
                <el-checkbox v-model="includeChildren[orgId]">含下级</el-checkbox>
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { dispatchApi, type PoolBody } from '../api/workflowDispatch'
import { errorText } from '../api/client'

const loading = ref(false)
const saving = ref(false)
const pools = ref<any[]>([])
const orgs = ref<any[]>([])
const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const selectedOrgIds = ref<number[]>([])
const includeChildren = reactive<Record<number, boolean>>({})
const treeRef = ref<any>(null)

const form = reactive<PoolBody>({
  code: '',
  name: '',
  description: '',
  status: 'ACTIVE',
  claimEnabled: true,
  orgs: []
})

const orgTree = computed(() => {
  const nodes = new Map<number, any>()
  for (const org of orgs.value) {
    nodes.set(Number(org.id), { id: Number(org.id), name: org.name, children: [] })
  }
  const roots: any[] = []
  for (const org of orgs.value) {
    const node = nodes.get(Number(org.id))
    const parentId = org.parentId == null ? null : Number(org.parentId)
    if (parentId && nodes.has(parentId)) nodes.get(parentId).children.push(node)
    else roots.push(node)
  }
  return roots
})

function orgName(orgId: any) {
  const org = orgs.value.find((item) => Number(item.id) === Number(orgId))
  return org?.name || `#${orgId}`
}

async function loadPools() {
  loading.value = true
  try {
    pools.value = await dispatchApi.pools()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

async function loadOrgs() {
  try {
    orgs.value = await dispatchApi.orgs()
  } catch (error) {
    console.error('[DispatchPool] 组织加载失败', error)
    orgs.value = []
  }
}

async function openCreate() {
  editingId.value = null
  resetForm()
  dialogOpen.value = true
  await loadOrgs()
  await nextTick()
  treeRef.value?.setCheckedKeys([])
}

async function openEdit(row: any) {
  editingId.value = Number(row.id)
  resetForm()
  form.code = row.code
  form.name = row.name
  form.description = row.description || ''
  form.status = row.status || 'ACTIVE'
  form.claimEnabled = row.claimEnabled !== false
  dialogOpen.value = true
  await loadOrgs()
  await nextTick()
  const ids = (row.orgs || []).map((org: any) => Number(org.orgId))
  selectedOrgIds.value = ids
  for (const org of row.orgs || []) includeChildren[Number(org.orgId)] = !!org.includeChildren
  treeRef.value?.setCheckedKeys(ids)
}

function resetForm() {
  form.code = ''
  form.name = ''
  form.description = ''
  form.status = 'ACTIVE'
  form.claimEnabled = true
  form.orgs = []
  selectedOrgIds.value = []
  for (const key of Object.keys(includeChildren)) delete includeChildren[Number(key)]
}

function onTreeCheck() {
  const keys = treeRef.value?.getCheckedKeys?.() || []
  selectedOrgIds.value = keys.map((key: any) => Number(key))
  for (const id of selectedOrgIds.value) {
    if (includeChildren[id] === undefined) includeChildren[id] = false
  }
}

async function submit() {
  if (!form.code.trim()) {
    ElMessage.warning('池编码不能为空')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('池名称不能为空')
    return
  }
  const body: PoolBody = {
    code: form.code.trim(),
    name: form.name.trim(),
    description: form.description?.trim() || undefined,
    status: form.status,
    claimEnabled: form.claimEnabled,
    orgs: selectedOrgIds.value.map((orgId) => ({
      orgId,
      includeChildren: !!includeChildren[orgId]
    }))
  }
  saving.value = true
  try {
    if (editingId.value) await dispatchApi.updatePool(editingId.value, body)
    else await dispatchApi.savePool(body)
    ElMessage.success('分单池已保存')
    dialogOpen.value = false
    await loadPools()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function suspend(row: any) {
  try {
    await ElMessageBox.confirm(
      `停用「${row.name}」后，该池不再参与分单；已进入池的任务仍可人工处理。`,
      '停用确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await dispatchApi.suspendPool(row.id)
    ElMessage.success('已停用')
    await loadPools()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(async () => {
  await Promise.allSettled([loadPools(), loadOrgs()])
})
</script>

<style scoped>
.empty { padding: 30px 0; color: var(--bp-muted); }
.empty .big { font-size: 28px; margin-bottom: 6px; }
.org-picker { display: flex; gap: 14px; width: 100%; align-items: stretch; }
.org-tree { flex: 1; min-width: 0; border: 1px solid #DCE7E1; border-radius: 10px; padding: 8px;
  max-height: 320px; overflow: auto; }
.org-selected { flex: 1; min-width: 0; border: 1px solid #DCE7E1; border-radius: 10px;
  max-height: 320px; overflow: auto; }
.org-selected-row { display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 6px 10px; border-bottom: 1px dashed #EBEFEC; }
.org-selected-row:last-child { border-bottom: none; }
.org-selected-name { font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
