<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">外部服务</h2>
        <p class="page-desc">
          维护外部服务的地址、认证方式与端点目录。端点只作为可选用目录供创建 Agent 工具时回填字段，
          不会自动注册成工具；凭证由服务端保管，页面不回显。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openCreateService">新建服务</el-button>
      </div>
    </div>

    <div class="service-layout">
      <div class="card service-list">
        <div class="toolbar">
          <el-input v-model="keyword" placeholder="搜索编码 / 名称 / 地址" clearable
                    style="width: 220px" :prefix-icon="Search" />
          <span class="hint">{{ filteredServices.length }} / {{ services.length }} 个服务</span>
        </div>
        <el-table :data="filteredServices" size="small" v-loading="loading"
                  highlight-current-row :current-row-key="selectedCode"
                  row-key="code" @row-click="selectService">
          <el-table-column label="服务" min-width="170">
            <template #default="{ row }">
              <div style="font-weight: 600">{{ row.name }}</div>
              <div class="code-chip">{{ row.code }}</div>
            </template>
          </el-table-column>
          <el-table-column label="地址" min-width="180" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono hint">{{ row.baseUrl }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="端点" width="70" align="center">
            <template #default="{ row }">{{ row.endpoints?.length || 0 }}</template>
          </el-table-column>
          <el-table-column v-if="canEdit" label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="openEditService(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click.stop="removeService(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !services.length" description="还没有外部服务，点击右上角新建" :image-size="70" />
      </div>

      <div class="card endpoint-panel">
        <template v-if="selectedService">
          <div class="toolbar">
            <div>
              <div style="font-weight: 600">{{ selectedService.name }} · 端点目录</div>
              <div class="hint">{{ selectedService.endpoints.length }} 个端点</div>
            </div>
            <div class="spacer"></div>
            <el-button v-if="canEdit" size="small" type="primary" :icon="Plus"
                       @click="openCreateEndpoint">新增端点</el-button>
          </div>
          <el-alert v-if="!selectedService.credentialConfigured" type="warning" :closable="false" show-icon
                    title="该服务尚未配置凭证，调用代理接口时会失败"
                    style="margin-bottom: 10px" />
          <el-table :data="selectedService.endpoints" size="small" v-loading="loading">
            <el-table-column label="端点" min-width="180">
              <template #default="{ row }">
                <div style="font-weight: 600">{{ row.toolName || row.code }}</div>
                <div class="code-chip">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column label="方法与路径" min-width="200">
              <template #default="{ row }">
                <span class="method">{{ row.method }}</span>
                <span class="mono" style="margin-left: 6px">{{ row.path }}</span>
              </template>
            </el-table-column>
            <el-table-column label="工具编码" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.toolCode || '—' }}</template>
            </el-table-column>
            <el-table-column label="执行方式" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.readonly ? 'success' : 'warning'" effect="plain">
                  {{ row.readonly ? '服务端只读' : '前端执行' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="排序" width="70" align="center" prop="sortNo" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="canEdit" label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditEndpoint(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeEndpoint(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && !selectedService.endpoints.length"
                    description="该服务还没有端点" :image-size="60" />
        </template>
        <el-empty v-else description="请从左侧选择一个服务" :image-size="80" />
      </div>
    </div>

    <el-dialog v-model="serviceFormVisible" :title="serviceEditing ? '编辑服务' : '新建服务'" width="640px">
      <el-form :model="serviceForm" label-width="120px">
        <el-form-item label="服务编码" required>
          <el-input v-model="serviceForm.code" :disabled="!!serviceEditing"
                    placeholder="小写字母/数字/中划线，例如 crm-service" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="serviceForm.name" placeholder="例如 CRM 系统" />
        </el-form-item>
        <el-form-item label="基础地址" required>
          <el-input v-model="serviceForm.baseUrl" placeholder="https://api.example.com" />
        </el-form-item>
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="认证方式">
              <el-select v-model="serviceForm.authType" filterable allow-create default-first-option
                         style="width: 100%" placeholder="选择或输入">
                <el-option v-for="item in AUTH_TYPES" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="凭证请求头">
              <el-input v-model="serviceForm.credentialHeader" placeholder="例如 Authorization" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="凭证">
          <el-input v-model="serviceForm.credential" type="password" show-password
                    :placeholder="serviceEditing ? '留空表示保持旧凭证不变' : '由服务端加密保管，不回显'" />
          <div class="hint">凭证加密存储，接口不会回显；编辑时留空即保持原值。</div>
        </el-form-item>
        <el-form-item label="静态请求头">
          <el-input v-model="serviceForm.staticHeadersText" type="textarea" :rows="4"
                    placeholder='{"X-Tenant":"baseplate"}' />
          <div class="hint">JSON 对象；留空表示没有静态请求头。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="serviceForm.status" active-value="ACTIVE" inactive-value="DISABLED"
                     active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="serviceFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="serviceSaving" @click="saveService">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="endpointFormVisible" :title="endpointEditing ? '编辑端点' : '新增端点'" width="720px">
      <el-form :model="endpointForm" label-width="120px">
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="端点编码" required>
              <el-input v-model="endpointForm.code" :disabled="!!endpointEditing"
                        placeholder="小写字母/数字/中划线" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序号">
              <el-input-number v-model="endpointForm.sortNo" :min="0" :max="9999"
                               controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="14">
          <el-col :span="8">
            <el-form-item label="方法">
              <el-select v-model="endpointForm.method" style="width: 100%">
                <el-option v-for="m in HTTP_METHODS" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="路径" required>
              <el-input v-model="endpointForm.path" placeholder="/v1/orders/{id}" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="执行方式">
          <el-switch v-model="endpointForm.readonly" active-text="服务端只读（代理执行）"
                     inactive-text="前端执行" />
          <div class="hint">服务端只读端点由代理转发请求；前端执行端点由页面侧处理调用结果。</div>
        </el-form-item>
        <el-row :gutter="14">
          <el-col :span="12">
            <el-form-item label="工具编码">
              <el-input v-model="endpointForm.toolCode" placeholder="选用后回填到工具表单的编码" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="工具名称">
              <el-input v-model="endpointForm.toolName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="工具说明">
          <el-input v-model="endpointForm.toolDescription" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="适用页面">
          <el-select v-model="endpointForm.pages" multiple filterable allow-create default-first-option
                     style="width: 100%" placeholder="选择页面，或输入自定义页面 key">
            <el-option v-for="item in PAGE_OPTIONS" :key="item.value"
                       :label="`${item.label}（${item.value}）`" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="所需权限">
          <el-select v-model="endpointForm.permissionCode" filterable allow-create clearable
                     default-first-option style="width: 100%" placeholder="可空；从当前账号权限选择，或输入自定义权限码">
            <el-option v-for="code in permissionOptions" :key="code" :label="code" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数 Schema">
          <el-input v-model="endpointForm.paramsText" type="textarea" :rows="8"
                    placeholder='{"type":"object","properties":{"field":{"type":"string"}}}' />
          <div class="hint">JSON 对象；模型按此 schema 生成调用参数。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="endpointForm.status" active-value="ACTIVE" inactive-value="DISABLED"
                     active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="endpointFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="endpointSaving" @click="saveEndpoint">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { errorText } from '../api/client'
import {
  integrationApi,
  type IntegrationEndpointPayload,
  type IntegrationEndpointView,
  type IntegrationServicePayload,
  type IntegrationServiceView
} from '../api/integration'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
/** 查看/编辑权限与后端权限码一致；超管始终放行。 */
const canView = computed(() => auth.has('integration:service:view') || auth.isSuperAdmin)
const canEdit = computed(() => auth.has('integration:service:edit') || auth.isSuperAdmin)

const AUTH_TYPES = ['NONE', 'BEARER', 'API_KEY', 'HEADER', 'BASIC']
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
/** 与路由保持一致的页面候选，允许手输自定义页面 key。 */
const PAGE_OPTIONS = [
  { value: '*', label: '全部页面' },
  { value: 'dashboard', label: '概览' },
  { value: 'identity/users', label: '用户管理' },
  { value: 'identity/roles', label: '角色权限' },
  { value: 'platform/apps', label: '开放平台' },
  { value: 'platform/apis', label: '接口目录' },
  { value: 'integration/services', label: '外部服务' },
  { value: 'audit/logs', label: '审计日志' },
  { value: 'rule/config', label: '规则引擎' },
  { value: 'workflow/start', label: '发起流程' },
  { value: 'workflow/todo', label: '我的待办' },
  { value: 'workflow/designer', label: '工作流' },
  { value: 'agent/config', label: '智能体配置' }
]

const permissionOptions = computed(() => auth.user?.permissions || [])

const services = ref<IntegrationServiceView[]>([])
const loading = ref(false)
const keyword = ref('')
const selectedCode = ref('')

const filteredServices = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return services.value
  return services.value.filter((item) =>
    [item.code, item.name, item.baseUrl]
      .some((value) => String(value || '').toLowerCase().includes(kw)))
})

/** 始终从最新列表里取选中服务，避免删除/刷新后拿到旧对象。 */
const selectedService = computed(() =>
  services.value.find((item) => item.code === selectedCode.value) || null)

onMounted(load)

async function load() {
  if (!canView.value) {
    ElMessage.warning('当前账号没有外部服务查看权限')
    return
  }
  loading.value = true
  try {
    const { data } = await integrationApi.services()
    services.value = Array.isArray(data) ? data : []
    if (!services.value.some((item) => item.code === selectedCode.value)) {
      selectedCode.value = services.value[0]?.code || ''
    }
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function selectService(row: IntegrationServiceView) {
  selectedCode.value = row.code
}

const serviceFormVisible = ref(false)
const serviceSaving = ref(false)
const serviceEditing = ref('')
const serviceForm = reactive({
  code: '',
  name: '',
  baseUrl: '',
  authType: 'NONE',
  credentialHeader: '',
  credential: '',
  staticHeadersText: '',
  status: 'ACTIVE'
})

function openCreateService() {
  serviceEditing.value = ''
  Object.assign(serviceForm, {
    code: '', name: '', baseUrl: '', authType: 'NONE', credentialHeader: '',
    credential: '', staticHeadersText: '', status: 'ACTIVE'
  })
  serviceFormVisible.value = true
}

function openEditService(row: IntegrationServiceView) {
  serviceEditing.value = row.code
  Object.assign(serviceForm, {
    code: row.code,
    name: row.name,
    baseUrl: row.baseUrl || '',
    authType: row.authType || 'NONE',
    credentialHeader: row.credentialHeader || '',
    credential: '',
    // 列表接口不返回凭证与静态请求头；留空即保持旧值
    staticHeadersText: '',
    status: row.status
  })
  serviceFormVisible.value = true
}

function parseJsonObject(text: string, label: string): Record<string, any> | null {
  if (!text.trim()) return {}
  try {
    const value = JSON.parse(text)
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      ElMessage.error(`${label}必须是 JSON 对象`)
      return null
    }
    return value
  } catch {
    ElMessage.error(`${label}不是合法 JSON`)
    return null
  }
}

async function saveService() {
  if (!serviceForm.code.trim() || !serviceForm.name.trim() || !serviceForm.baseUrl.trim()) {
    ElMessage.warning('服务编码、名称、基础地址为必填项')
    return
  }
  const staticHeaders = parseJsonObject(serviceForm.staticHeadersText, '静态请求头')
  if (!staticHeaders) return
  const payload: IntegrationServicePayload = {
    code: serviceForm.code.trim(),
    name: serviceForm.name.trim(),
    baseUrl: serviceForm.baseUrl.trim(),
    authType: serviceForm.authType,
    credentialHeader: serviceForm.credentialHeader.trim(),
    credential: serviceForm.credential,
    status: serviceForm.status
  }
  // 编辑时列表接口不返回静态请求头；留空就不带该字段，由后端保持旧值
  if (!serviceEditing.value || serviceForm.staticHeadersText.trim()) {
    payload.staticHeaders = staticHeaders as Record<string, string>
  }
  serviceSaving.value = true
  try {
    if (serviceEditing.value) {
      await integrationApi.updateService(serviceEditing.value, payload)
    } else {
      await integrationApi.createService(payload)
    }
    ElMessage.success('服务已保存')
    serviceFormVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    serviceSaving.value = false
  }
}

async function removeService(row: IntegrationServiceView) {
  try {
    await ElMessageBox.confirm(`确认删除服务「${row.name}」及其全部端点？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await integrationApi.deleteService(row.code)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

const endpointFormVisible = ref(false)
const endpointSaving = ref(false)
const endpointEditing = ref('')
const endpointForm = reactive({
  code: '',
  path: '',
  method: 'GET',
  readonly: false,
  toolCode: '',
  toolName: '',
  toolDescription: '',
  pages: ['workflow/designer'] as string[],
  permissionCode: '',
  paramsText: '{\n  "type": "object",\n  "properties": {}\n}',
  status: 'ACTIVE',
  sortNo: 0
})

function openCreateEndpoint() {
  endpointEditing.value = ''
  Object.assign(endpointForm, {
    code: '', path: '', method: 'GET', readonly: false,
    toolCode: '', toolName: '', toolDescription: '',
    pages: ['workflow/designer'], permissionCode: '',
    paramsText: '{\n  "type": "object",\n  "properties": {}\n}',
    status: 'ACTIVE', sortNo: 0
  })
  endpointFormVisible.value = true
}

function openEditEndpoint(row: IntegrationEndpointView) {
  endpointEditing.value = row.code
  Object.assign(endpointForm, {
    code: row.code,
    path: row.path || '',
    method: row.method || 'GET',
    readonly: !!row.readonly,
    toolCode: row.toolCode || '',
    toolName: row.toolName || '',
    toolDescription: row.toolDescription || '',
    pages: [...(row.pages || [])],
    permissionCode: row.permissionCode || '',
    paramsText: JSON.stringify(row.paramsSchema || { type: 'object', properties: {} }, null, 2),
    status: row.status || 'ACTIVE',
    sortNo: Number(row.sortNo || 0)
  })
  endpointFormVisible.value = true
}

async function saveEndpoint() {
  const service = selectedService.value
  if (!service) return
  if (!endpointForm.code.trim() || !endpointForm.path.trim()) {
    ElMessage.warning('端点编码与路径为必填项')
    return
  }
  const paramsSchema = parseJsonObject(endpointForm.paramsText, '参数 Schema')
  if (!paramsSchema) return
  const payload: IntegrationEndpointPayload = {
    code: endpointForm.code.trim(),
    path: endpointForm.path.trim(),
    method: endpointForm.method,
    readonly: endpointForm.readonly,
    toolCode: endpointForm.toolCode.trim(),
    toolName: endpointForm.toolName.trim(),
    toolDescription: endpointForm.toolDescription.trim(),
    paramsSchema,
    pages: endpointForm.pages,
    permissionCode: endpointForm.permissionCode.trim() || '',
    status: endpointForm.status,
    sortNo: endpointForm.sortNo
  }
  endpointSaving.value = true
  try {
    if (endpointEditing.value) {
      await integrationApi.updateEndpoint(service.code, endpointEditing.value, payload)
    } else {
      await integrationApi.createEndpoint(service.code, payload)
    }
    ElMessage.success('端点已保存')
    endpointFormVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    endpointSaving.value = false
  }
}

async function removeEndpoint(row: IntegrationEndpointView) {
  const service = selectedService.value
  if (!service) return
  try {
    await ElMessageBox.confirm(`确认删除端点「${row.code}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await integrationApi.deleteEndpoint(service.code, row.code)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}
</script>

<style scoped>
.service-layout {
  display: grid;
  grid-template-columns: minmax(380px, 1fr) minmax(420px, 1.2fr);
  gap: 12px;
  align-items: start;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.toolbar .spacer { flex: 1; }
.hint { font-size: 11px; color: var(--bp-muted); }
.code-chip {
  display: inline-block;
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  color: var(--bp-muted);
}
.mono { font-family: Consolas, Monaco, monospace; }
.method {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: #E8F1ED;
  color: #17564A;
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  font-weight: 600;
}
.endpoint-panel :deep(.el-alert) { padding: 6px 10px; }
@media (max-width: 1100px) {
  .service-layout { grid-template-columns: 1fr; }
}
</style>
