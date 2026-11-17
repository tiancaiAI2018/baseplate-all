<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">流程演练</h2>
        <p class="page-desc">
          一键生成组织、角色、测试账号和演示流程；按账号查看待办，验证找人逻辑、会签比例、
          网关条件、必填原因与跨租户隔离。
        </p>
      </div>
      <div class="page-actions">
        <el-button @click="loadStatus">刷新</el-button>
        <el-button type="primary" @click="initLab">初始化演练环境</el-button>
      </div>
    </div>

    <div class="lab-stats">
      <div><b>{{ status.initialized ? '已就绪' : '未初始化' }}</b><span>演练环境</span></div>
      <div><b>{{ status.accounts?.length || 0 }}</b><span>测试账号</span></div>
      <div><b>{{ status.definitions?.length ? 'v' + status.definitions[0].version : '—' }}</b><span>演示流程版本</span></div>
      <div><b>{{ status.otherTenant?.tenant || '—' }}</b><span>隔离验证租户</span></div>
    </div>

    <el-row :gutter="14" style="margin-top: 14px">
      <el-col :span="10">
        <div class="card" style="box-shadow: none">
          <h3 class="card-title"><span class="dot"></span>发起请假申请（真实表单）</h3>
          <p class="hint">天数 &gt; 3 会走三人会签；≤ 3 直接到财务。</p>
          <FormRenderer ref="renderer" :rule="formRule" v-model="formData" />
          <el-button type="primary" style="margin-top: 12px" @click="start">发起实例</el-button>
        </div>
        <div class="card" style="box-shadow: none; margin-top: 14px">
          <h3 class="card-title"><span class="dot"></span>测试账号</h3>
          <el-table :data="status.accounts || []" size="small" max-height="280">
            <el-table-column prop="username" label="账号" width="110" />
            <el-table-column label="密码" width="150"><template #default>{{ status.password }}</template></el-table-column>
            <el-table-column label="待办" width="80">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="loadTasks(row.username)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="14">
        <div class="card" style="box-shadow: none">
          <div class="toolbar">
            <h3 class="card-title" style="margin: 0"><span class="dot"></span>待办办理（{{ currentAccount || '未选择' }}）</h3>
            <div class="spacer"></div>
            <el-button size="small" @click="loadTasks(currentAccount)">刷新</el-button>
          </div>
          <el-table :data="tasks" size="small" max-height="240">
            <el-table-column prop="name" label="任务" width="130" />
            <el-table-column prop="assignee" label="办理人 ID" width="100" />
            <el-table-column label="所属实例" min-width="140" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.processInstanceId }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="190">
              <template #default="{ row }">
                <el-button size="small" type="success" plain @click="complete(row, 'APPROVE')">同意</el-button>
                <el-button size="small" type="danger" plain @click="complete(row, 'REJECT')">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!tasks.length" description="该账号当前没有待办" :image-size="60" />
        </div>

        <div class="card" style="box-shadow: none; margin-top: 14px">
          <div class="toolbar">
            <h3 class="card-title" style="margin: 0"><span class="dot"></span>自动验证</h3>
            <div class="spacer"></div>
            <el-button type="primary" size="small" :loading="running" @click="autoVerify">跑一遍完整链路</el-button>
          </div>
          <p class="hint">自动发起 → 主管审批 → 会签 2/3 通过 → 财务确认，并检查跨租户看不到流程。</p>
          <el-table :data="checks" size="small" max-height="260">
            <el-table-column prop="step" label="检查项" min-width="170" />
            <el-table-column prop="expected" label="期望" min-width="150" />
            <el-table-column prop="actual" label="实际" min-width="150" />
            <el-table-column label="结果" width="80">
              <template #default="{ row }">
                <el-tag :type="row.pass ? 'success' : 'danger'" size="small">{{ row.pass ? '通过' : '未通过' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'
import FormRenderer from './FormRenderer.vue'

const auth = useAuthStore()
const status = ref<any>({})
const formRule = ref<any[]>([])
const formData = ref<Record<string, any>>({ days: 5, reason: '年假回家', startDate: '2026-11-20' })
const renderer = ref<any>(null)
const tasks = ref<any[]>([])
const currentAccount = ref('')
const checks = ref<any[]>([])
const running = ref(false)
const userIds = ref<Record<string, string>>({})

const PROCESS_KEY = 'lab-leave'
const FORM_CODE = 'lab_leave_form'

function token(): string { return auth.token }
function H(): any { return { Authorization: 'Bearer ' + token() } }

async function loadStatus() {
  try {
    const { data } = await http.get('/api/workflow/lab/status')
    status.value = data
    if (data.initialized) await loadForm()
  } catch (error) { ElMessage.error(errorText(error)) }
}

async function loadForm() {
  try {
    const { data } = await http.get(`/api/metadata/FORM/${FORM_CODE}`)
    formRule.value = data.uiSchemaJson || '[]'
  } catch { formRule.value = [] }
}

async function loadUserIds() {
  try {
    const { data } = await http.get('/api/identity/users', { params: { page: 1, size: 100 } })
    userIds.value = Object.fromEntries(data.items.map((u: any) => [u.username, String(u.id)]))
  } catch { userIds.value = {} }
}

async function initLab() {
  try {
    const { data } = await http.post('/api/workflow/lab/init')
    ElMessage.success('演练环境已就绪，账号密码：' + data.password)
    await loadStatus()
    await loadUserIds()
  } catch (error) { ElMessage.error(errorText(error)) }
}

async function start() {
  try {
    const values = renderer.value?.formData?.() || formData.value
    const { data } = await http.post('/api/workflow/instances', {
      processKey: PROCESS_KEY,
      businessKey: 'LAB-' + Date.now(),
      formCode: FORM_CODE,
      formData: values,
      variables: { initiator: userIds.value['zhangsan'] }
    })
    ElMessage.success('已发起：' + data.processInstanceId)
    await loadTasks('lisi')
  } catch (error) { ElMessage.error(errorText(error)) }
}

async function loadTasks(username: string) {
  if (!username) return
  currentAccount.value = username
  const id = userIds.value[username]
  if (!id) { await loadUserIds() }
  try {
    const { data } = await http.get('/api/workflow/tasks', {
      params: { assignee: userIds.value[username], page: 1, size: 50 }
    })
    tasks.value = data.items || []
  } catch (error) { ElMessage.error(errorText(error)) }
}

async function complete(task: any, decision: string) {
  try {
    let reason = '同意'
    if (decision === 'REJECT') {
      const { value } = await ElMessageBox.prompt('驳回原因（该节点配置为必填，至少 5 个字）', '驳回',
        { inputPlaceholder: '如：材料不全，请补充后再提交' })
      reason = value
    }
    await http.post(`/api/workflow/tasks/${task.taskId}/complete`, {
      decision, values: { reason, formData: formData.value }
    })
    ElMessage.success('已办理')
    await loadTasks(currentAccount.value)
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

function record(step: string, expected: string, actual: string, pass: boolean) {
  checks.value.push({ step, expected, actual, pass })
}

async function autoVerify() {
  running.value = true
  checks.value = []
  try {
    await loadUserIds()
    const ids = userIds.value
    const values = { days: 5, reason: '自动验证', startDate: '2026-11-20' }
    const started = await http.post('/api/workflow/instances', {
      processKey: PROCESS_KEY, businessKey: 'AUTO-' + Date.now(),
      formCode: FORM_CODE, formData: values, variables: { initiator: ids['zhangsan'] }
    })
    const iid = started.data.processInstanceId

    const runningTasks = async () =>
      (await http.get(`/api/workflow/instances/${iid}/tasks`)).data

    let current = await runningTasks()
    record('找人逻辑：部门主管', '李四（lisi）', String(current[0]?.assignee), current[0]?.assignee === ids['lisi'])

    const bad = await http.post(`/api/workflow/tasks/${current[0].taskId}/complete`,
      { decision: 'REJECT', values: { reason: '' } }, { validateStatus: () => true } as any)
    record('驳回必填原因', '400 拦下', String(bad.status), bad.status === 400)

    await http.post(`/api/workflow/tasks/${current[0].taskId}/complete`,
      { decision: 'APPROVE', values: { reason: '情况属实' } })
    current = await runningTasks()
    record('网关条件 days=5', '进入三人会签', `${current.length} 个会签任务`, current.length === 3)

    const expectedSigners = [ids['wangwu'], ids['zhaoliu'], ids['sunqi']]
    record('会签成员解析', '王五/赵六/孙七', current.map((t: any) => t.assignee).join(','),
      current.every((t: any) => expectedSigners.includes(String(t.assignee))))

    for (const task of current.slice(0, 2)) {
      await http.post(`/api/workflow/tasks/${task.taskId}/complete`,
        { decision: 'APPROVE', values: { reason: '同意' } })
    }
    current = await runningTasks()
    record('会签比例 60%', '2 人同意即进入财务',
      current.map((t: any) => t.name + ':' + t.assignee).join(','),
      current.length === 1 && current[0].assignee === ids['caiwu'])

    for (const task of current) {
      await http.post(`/api/workflow/tasks/${task.taskId}/complete`,
        { decision: 'APPROVE', values: { reason: '已登记' } })
    }
    const detail = (await http.get(`/api/workflow/instances/${iid}/detail`)).data
    record('流程走到结束', 'ended=true', String(detail.ended), !!detail.ended)

    // 跨租户验证必须用「另一个租户的令牌」发请求：
    // 走 http 客户端会被请求拦截器换成当前控制台的令牌，检查就变成了自我安慰。
    // 这里用裸 axios（不挂拦截器），也避免它 401 时把管理员的登录态清掉。
    const other = await axios.post('/api/auth/login', {
      username: 'demo2user', password: status.value.password || 'Lab@2026!Secure', tenant: 'demo2'
    })
    const otherDefs = await axios.get('/api/workflow/definitions', {
      headers: { Authorization: 'Bearer ' + other.data.accessToken }
    })
    const otherStart = await axios.post('/api/workflow/instances', {
      processKey: PROCESS_KEY, businessKey: 'CROSS-' + Date.now(), variables: {}
    }, {
      headers: { Authorization: 'Bearer ' + other.data.accessToken },
      validateStatus: () => true
    })
    const visible = otherDefs.data.some((d: any) => d.key === PROCESS_KEY)
    record('跨租户隔离', 'demo2 既看不到也发不起',
      `可见=${visible}，发起=${otherStart.status}`,
      !visible && otherStart.status >= 400)

    ElMessage.success(checks.value.every((c) => c.pass) ? '全部验证通过' : '存在未通过项，请看检查表')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    running.value = false
  }
}

onMounted(async () => {
  await loadStatus()
  await loadUserIds()
})
</script>

<style scoped>
.lab-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.lab-stats > div { background: #F4F8F6; border: 1px solid #DCE7E1; border-radius: 12px; padding: 14px 16px; }
.lab-stats b { display: block; font-size: 22px; color: #17564A; }
.lab-stats span { font-size: 12px; color: #6B7671; }
</style>
