<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">分单中心</h1>
        <p class="page-desc">
          签到决定是否进入候选池；抢单只对组织池内的人可见；拒绝会按池策略记冷却或降权。
        </p>
      </div>
      <div class="page-actions">
        <el-button :loading="loading" plain @click="refreshAll">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <div class="card checkin-card">
      <div class="checkin-main">
        <div>
          <div class="checkin-title">
            {{ checkin.checkedIn ? '今日已签到' : '今日未签到' }}
            <el-tag v-if="checkin.checkedIn" type="success" size="small" effect="dark">在岗</el-tag>
            <el-tag v-else type="info" size="small">未签到不会进入自动派单候选</el-tag>
          </div>
          <div class="hint">
            {{ checkin.date || '-' }}
            <span v-if="checkin.checkinAt"> · 签到 {{ fmt(checkin.checkinAt) }}</span>
            <span v-if="checkin.checkoutAt"> · 签退 {{ fmt(checkin.checkoutAt) }}</span>
            <span v-if="state.onlineStatus"> · 状态 {{ state.onlineStatus }}</span>
          </div>
        </div>
        <div class="checkin-actions">
          <el-button v-if="!checkin.checkedIn" type="primary" :loading="acting" @click="doCheckin">
            签到上班
          </el-button>
          <template v-else>
            <el-button :loading="acting" @click="doHeartbeat">保持在线</el-button>
            <el-button type="danger" plain :loading="acting" @click="doCheckout">签退下班</el-button>
          </template>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">待抢任务</div>
        <div class="stat-value">{{ stats.openPoolTasks ?? 0 }}</div>
        <div class="hint">组织池内 OPEN 状态</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日已派</div>
        <div class="stat-value">{{ stats.assignedToday ?? 0 }}</div>
        <div class="hint">自动 + 人工指派</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日已领</div>
        <div class="stat-value">{{ stats.claimedToday ?? 0 }}</div>
        <div class="hint">含抢单与直接指派</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日拒绝</div>
        <div class="stat-value">{{ stats.refusedToday ?? 0 }}</div>
        <div class="hint">按池策略执行惩罚</div>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane name="pool">
          <template #label>
            抢单大厅
            <el-badge v-if="poolRows.length" :value="poolRows.length" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="mine">
          <template #label>
            我的分单待办
            <el-badge v-if="myRows.length" :value="myRows.length" :max="99" type="info" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="rate">
          <template #label>
            待评价
            <el-badge v-if="rateRows.length" :value="rateRows.length" :max="99" type="warning" class="tab-badge" />
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-table v-if="tab === 'pool'" v-loading="loading" :data="poolRows">
        <el-table-column label="任务" min-width="180">
          <template #default="{ row }">
            <b>{{ row.taskName || row.nodeId }}</b>
            <span class="code-chip" style="margin-left: 6px">{{ row.nodeId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="池" width="150">
          <template #default="{ row }">{{ poolName(row.poolId) }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="row.priority > 5 ? 'danger' : row.priority > 0 ? 'warning' : 'info'" size="small">
              {{ row.priority ?? 0 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到达时间" width="170">
          <template #default="{ row }">{{ fmt(row.taskCreatedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" :loading="acting" @click="claim(row)">抢单</el-button>
            <el-button v-if="canDispatch" size="small" @click="openAssign(row)">指派</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">📭</div>
            当前组织池里没有可抢任务
          </div>
        </template>
      </el-table>

      <el-table v-else-if="tab === 'mine'" v-loading="loading" :data="myRows" class="clickable"
                @row-click="openTask">
        <el-table-column label="任务" min-width="180">
          <template #default="{ row }">
            <b>{{ row.taskName || row.nodeId }}</b>
            <span class="code-chip" style="margin-left: 6px">{{ row.nodeId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="池" width="150">
          <template #default="{ row }">{{ poolName(row.poolId) }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="row.priority > 5 ? 'danger' : row.priority > 0 ? 'warning' : 'info'" size="small">
              {{ row.priority ?? 0 }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到达时间" width="170">
          <template #default="{ row }">{{ fmt(row.taskCreatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click.stop="openTask(row)">办理</el-button>
            <el-button size="small" @click.stop="openRefuse(row)">拒绝</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">🎉</div>
            名下暂时没有分单任务
          </div>
        </template>
      </el-table>

      <el-table v-else v-loading="loading" :data="rateRows">
        <el-table-column label="任务" min-width="200">
          <template #default="{ row }"><b>{{ row.taskName || row.nodeId }}</b></template>
        </el-table-column>
        <el-table-column label="办理人" width="150">
          <template #default="{ row }">{{ row.assignee || '—' }}</template>
        </el-table-column>
        <el-table-column label="办结时间" width="180">
          <template #default="{ row }">{{ fmt(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="warning" size="small" plain @click="openRate(row)">评价</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">
            <div class="big">⭐</div>
            没有待评价的已办任务
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="assignOpen" title="指派任务" width="520px">
      <el-form label-width="90px">
        <el-form-item label="任务">
          <span class="mono">{{ assignRow?.taskId }}</span>
        </el-form-item>
        <el-form-item label="指派给">
          <el-select v-model="assignUserId" filterable style="width: 100%" placeholder="选择池内成员">
            <el-option v-for="user in assignUsers" :key="user.userId"
                       :label="`${user.displayName}（${user.username}）`" :value="user.userId" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canCrossOrg" label="跨组织">
          <el-checkbox v-model="assignCrossOrg">允许指派到池外组织（需审计）</el-checkbox>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="assignReason" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" :disabled="!assignUserId" @click="submitAssign">确认指派</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="refuseOpen" title="拒绝 / 退回任务" width="520px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-radio-group v-model="refuseType">
            <el-radio-button value="ASSIGN_REFUSAL">拒绝指派</el-radio-button>
            <el-radio-button value="RELEASE">退回池里</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="原因">
          <el-select v-model="refuseCode" style="width: 100%">
            <el-option v-for="item in reasons" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="refuseCode === 'OTHER'" label="说明">
          <el-input v-model="refuseText" type="textarea" :rows="2" maxlength="200" show-word-limit
                    placeholder="请填写具体原因" />
        </el-form-item>
        <el-alert type="warning" :closable="false" show-icon
                  title="拒绝会记录到分单档案，并按池策略执行冷却、降权或升级通知。" />
      </el-form>
      <template #footer>
        <el-button @click="refuseOpen = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="submitRefuse">确认提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rateOpen" title="服务评价" width="480px">
      <el-form label-width="80px">
        <el-form-item label="评分">
          <el-rate v-model="rateScore" :max="5" show-score />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="rateTags" placeholder="多个标签用逗号分隔，如：响应快,专业" />
        </el-form-item>
        <el-form-item label="评价">
          <el-input v-model="rateComment" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rateOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitRate">提交评价</el-button>
      </template>
    </el-dialog>

    <TaskHandleDrawer v-model="handleOpen" :task-id="activeTaskId" :done-info="null"
                      @handled="onHandled" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { dispatchApi } from '../api/workflowDispatch'
import { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'
import TaskHandleDrawer from '../components/TaskHandleDrawer.vue'

const auth = useAuthStore()
const canDispatch = computed(() => auth.has('wf:dispatch') || auth.isSuperAdmin)
const canCrossOrg = computed(() => auth.has('wf:dispatch:cross-org') || auth.isSuperAdmin)

const tab = ref<'pool' | 'mine' | 'rate'>('pool')
const loading = ref(false)
const acting = ref(false)

const checkin = ref<any>({})
const state = ref<any>({})
const stats = ref<any>({})
const poolRows = ref<any[]>([])
const myRows = ref<any[]>([])
const rateRows = ref<any[]>([])
const pools = ref<any[]>([])

const reasons = [
  { value: 'BUSY', label: '当前任务过多' },
  { value: 'SKILL_MISMATCH', label: '技能不匹配' },
  { value: 'OUT_OF_SCOPE', label: '不在职责范围' },
  { value: 'CONFLICT', label: '存在利益冲突' },
  { value: 'OTHER', label: '其他（需填写说明）' }
]

const assignOpen = ref(false)
const assignRow = ref<any>(null)
const assignUsers = ref<any[]>([])
const assignUserId = ref('')
const assignCrossOrg = ref(false)
const assignReason = ref('')

const refuseOpen = ref(false)
const refuseRow = ref<any>(null)
const refuseType = ref('ASSIGN_REFUSAL')
const refuseCode = ref('BUSY')
const refuseText = ref('')

const rateOpen = ref(false)
const rateRow = ref<any>(null)
const rateScore = ref(5)
const rateTags = ref('')
const rateComment = ref('')

const handleOpen = ref(false)
const activeTaskId = ref('')

let heartbeatTimer: ReturnType<typeof setInterval> | null = null

function fmt(value: any) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function poolName(poolId: any) {
  const pool = pools.value.find((item) => Number(item.id) === Number(poolId))
  return pool ? pool.name : `#${poolId ?? '-'}`
}

async function loadBasics() {
  try {
    const data = await dispatchApi.today()
    checkin.value = data || {}
    state.value = data?.state || {}
  } catch (error) {
    console.error('[DispatchCenter] 签到状态加载失败', error)
    checkin.value = {}
  }
  try {
    pools.value = await dispatchApi.pools()
  } catch (error) {
    console.error('[DispatchCenter] 分单池加载失败', error)
    pools.value = []
  }
  try {
    stats.value = await dispatchApi.stats()
  } catch (error) {
    console.error('[DispatchCenter] 统计加载失败', error)
    stats.value = {}
  }
}

async function loadPoolTasks() {
  try {
    poolRows.value = await dispatchApi.poolTasks()
  } catch (error) {
    console.error('[DispatchCenter] 抢单列表加载失败', error)
    poolRows.value = []
  }
}

async function loadMyTasks() {
  try {
    myRows.value = await dispatchApi.myTasks()
  } catch (error) {
    console.error('[DispatchCenter] 我的分单任务加载失败', error)
    myRows.value = []
  }
}

async function loadRatable() {
  try {
    rateRows.value = await dispatchApi.ratableTasks()
  } catch (error) {
    console.error('[DispatchCenter] 待评价任务加载失败', error)
    rateRows.value = []
  }
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.allSettled([loadBasics(), loadPoolTasks(), loadMyTasks(), loadRatable()])
  } finally {
    loading.value = false
  }
}

async function onTabChange() {
  if (tab.value === 'pool') await loadPoolTasks()
  else if (tab.value === 'mine') await loadMyTasks()
  else await loadRatable()
}

async function doCheckin() {
  acting.value = true
  try {
    const data = await dispatchApi.checkin()
    checkin.value = data || {}
    state.value = data?.state || {}
    ElMessage.success('签到成功，已进入分单候选')
    await loadPoolTasks()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    acting.value = false
  }
}

async function doCheckout() {
  acting.value = true
  try {
    const data = await dispatchApi.checkout()
    checkin.value = data || {}
    state.value = data?.state || {}
    ElMessage.success('已签退，不再参与自动派单')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    acting.value = false
  }
}

async function doHeartbeat() {
  try {
    const data = await dispatchApi.heartbeat('ONLINE')
    checkin.value = data || {}
    state.value = data?.state || {}
  } catch (error) {
    console.error('[DispatchCenter] 心跳失败', error)
  }
}

async function claim(row: any) {
  try {
    await ElMessageBox.confirm(`确认认领任务「${row.taskName || row.nodeId}」？认领后需要尽快办理。`,
      '抢单确认', { type: 'warning' })
  } catch {
    return
  }
  acting.value = true
  try {
    await dispatchApi.claim(row.id)
    ElMessage.success('认领成功，任务已进入我的待办')
    await Promise.allSettled([loadPoolTasks(), loadMyTasks(), loadBasics()])
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    acting.value = false
  }
}

async function openAssign(row: any) {
  assignRow.value = row
  assignUserId.value = ''
  assignCrossOrg.value = false
  assignReason.value = ''
  assignOpen.value = true
  try {
    assignUsers.value = await dispatchApi.assignableUsers(row.poolId)
  } catch (error) {
    console.error('[DispatchCenter] 可指派用户加载失败', error)
    assignUsers.value = []
    ElMessage.error(errorText(error))
  }
}

async function submitAssign() {
  if (!assignRow.value?.taskId || !assignUserId.value) return
  acting.value = true
  try {
    await dispatchApi.assign(assignRow.value.taskId, {
      userId: assignUserId.value,
      crossOrg: assignCrossOrg.value,
      reason: assignReason.value || undefined
    })
    ElMessage.success('指派成功')
    assignOpen.value = false
    await Promise.allSettled([loadPoolTasks(), loadBasics()])
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    acting.value = false
  }
}

function openRefuse(row: any) {
  refuseRow.value = row
  refuseType.value = 'ASSIGN_REFUSAL'
  refuseCode.value = 'BUSY'
  refuseText.value = ''
  refuseOpen.value = true
}

async function submitRefuse() {
  if (!refuseRow.value?.taskId) return
  if (refuseCode.value === 'OTHER' && !refuseText.value.trim()) {
    ElMessage.warning('选择「其他」时必须填写说明')
    return
  }
  acting.value = true
  try {
    const result = await dispatchApi.refuse(refuseRow.value.taskId, {
      type: refuseType.value,
      reasonCode: refuseCode.value,
      reasonText: refuseText.value.trim() || undefined
    })
    ElMessage.success(result?.duplicated ? '该任务已拒绝过，未重复执行策略' : '已提交，任务回到池里')
    refuseOpen.value = false
    await Promise.allSettled([loadMyTasks(), loadPoolTasks(), loadBasics()])
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    acting.value = false
  }
}

function openRate(row: any) {
  rateRow.value = row
  rateScore.value = 5
  rateTags.value = ''
  rateComment.value = ''
  rateOpen.value = true
}

async function submitRate() {
  if (!rateRow.value?.taskId) return
  acting.value = true
  try {
    await dispatchApi.rate(rateRow.value.taskId, {
      score: rateScore.value,
      tags: rateTags.value.split(/[,，]/).map((item) => item.trim()).filter(Boolean),
      comment: rateComment.value.trim() || undefined
    })
    ElMessage.success('评价已提交')
    rateOpen.value = false
    await loadRatable()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    acting.value = false
  }
}

function openTask(row: any) {
  activeTaskId.value = row.taskId
  handleOpen.value = true
}

async function onHandled() {
  await Promise.allSettled([loadMyTasks(), loadRatable(), loadBasics()])
}

onMounted(async () => {
  await refreshAll()
  heartbeatTimer = setInterval(() => {
    if (checkin.value?.checkedIn) doHeartbeat()
  }, 60000)
})

onBeforeUnmount(() => {
  if (heartbeatTimer) clearInterval(heartbeatTimer)
})
</script>

<style scoped>
.checkin-card { padding: 18px 20px; }
.checkin-main { display: flex; align-items: center; justify-content: space-between; gap: 18px; flex-wrap: wrap; }
.checkin-title { font-size: 16px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
.checkin-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin: 18px 0; }
.stat-card { background: #fff; border: 1px solid #DCE7E1; border-radius: 12px; padding: 16px 18px; }
.stat-label { color: var(--bp-muted); font-size: 12.5px; }
.stat-value { font-size: 26px; font-weight: 700; margin: 6px 0 2px; letter-spacing: .4px; }
.tab-badge { margin-left: 6px; }
.empty { padding: 30px 0; color: var(--bp-muted); }
.empty .big { font-size: 28px; margin-bottom: 6px; }
</style>
