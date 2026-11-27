<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">分单看板</h1>
        <p class="page-desc">
          每次派单的候选人、打分和淘汰原因都会留档；拒绝与惩罚可追溯、可撤销。
        </p>
      </div>
      <div class="page-actions">
        <el-select v-model="poolId" clearable placeholder="全部分单池" style="width: 180px"
                   @change="onPoolChange">
          <el-option v-for="pool in pools" :key="pool.id" :label="pool.name" :value="pool.id" />
        </el-select>
        <el-button plain :loading="loading" @click="refreshAll">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button v-if="canManage" type="warning" plain :loading="scanning" @click="runTimeoutScan">
          立即执行超时扫描
        </el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">启用中的池</div>
        <div class="stat-value">{{ stats.pools ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日签到在岗</div>
        <div class="stat-value">{{ stats.checkedInToday ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">待抢任务</div>
        <div class="stat-value">{{ stats.openPoolTasks ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日派出</div>
        <div class="stat-value">{{ stats.assignedToday ?? 0 }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">平均等待</div>
        <div class="stat-value">{{ waitText(stats.avgWaitSeconds) }}</div>
        <div class="hint">从进池到被认领</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日拒绝</div>
        <div class="stat-value">{{ stats.refusedToday ?? 0 }}</div>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane label="决策日志" name="logs" />
        <el-tab-pane label="拒绝记录" name="refusals" />
        <el-tab-pane label="惩罚记录" name="penalties" />
        <el-tab-pane label="评价记录" name="ratings" />
      </el-tabs>

      <div v-if="tab === 'logs'" class="logs-toolbar">
        <el-input v-model="logKeyword" placeholder="搜单号 / 任务名 / 节点 / 任务ID" clearable
                  :prefix-icon="Search" style="width: 260px" @keyup.enter="searchLogs" />
        <el-select v-model="logOutcome" clearable placeholder="全部结果" style="width: 130px" @change="searchLogs">
          <el-option v-for="o in outcomeOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" @click="searchLogs">查询</el-button>
        <el-button @click="resetLogFilter">重置</el-button>
        <div class="spacer"></div>
        <el-checkbox v-model="mergeLogs" @change="loadLogs">合并重复</el-checkbox>
      </div>
      <el-table v-if="tab === 'logs'" v-loading="loading" :data="displayLogs" :row-key="logRowKey"
                :expand-row-keys="expandedLogs" @expand-change="onLogExpand">
        <el-table-column type="expand" width="42">
          <template #default="{ row }">
            <div v-if="row.group.length > 1" class="log-group">
              <div class="log-group-head">同一单子的 {{ row.group.length }} 次连续决策（最新在上）</div>
              <div v-for="item in row.group" :key="item.id" class="log-group-row">
                <span class="mono">{{ fmt(item.createdAt) }}</span>
                <el-tag :type="outcomeType(item.outcome)" size="small" effect="dark">{{ item.outcome }}</el-tag>
                <span class="log-group-reason">{{ item.reason || '—' }}</span>
                <el-button link type="primary" @click="openDetail(item)">明细</el-button>
              </div>
            </div>
            <div v-else class="log-group log-group-single">这条决策没有重复记录。</div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">
            <div>{{ fmt(row.createdAt) }}</div>
            <div v-if="row.group.length > 1" class="log-repeat-hint">
              连续 {{ row.group.length }} 次 · 最新 {{ fmt(row.createdAt) }} / 最早 {{ fmt(row.group[row.group.length - 1].createdAt) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="任务 / 单号" min-width="200">
          <template #default="{ row }">
            <div class="task-cell">
              <span class="task-name">{{ row.taskName || row.nodeId || '—' }}</span>
              <span v-if="row.businessKey" class="task-biz">{{ row.businessKey }}</span>
              <span v-else class="code-chip">{{ row.nodeId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="130">
          <template #default="{ row }">
            <el-tag :type="outcomeType(row.outcome)" size="small" effect="dark">{{ row.outcome }}</el-tag>
            <span v-if="row.group.length > 1" class="repeat-count">×{{ row.group.length }}</span>
          </template>
        </el-table-column>
        <el-table-column label="算法" width="120">
          <template #default="{ row }">{{ row.algorithm || '—' }}</template>
        </el-table-column>
        <el-table-column label="派给" width="170">
          <template #default="{ row }">{{ chosenName(row) || row.chosenUserId || '—' }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="明细" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row.group[0])">查看</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="empty">暂无分单决策记录</div></template>
      </el-table>
      <el-pagination v-if="tab === 'logs'" class="logs-pager" background
                     v-model:current-page="logPage" v-model:page-size="logSize"
                     :page-sizes="[10, 20, 50, 100]" :total="logTotal"
                     layout="total, sizes, prev, pager, next, jumper"
                     @size-change="onLogSizeChange" @current-change="loadLogs" />

      <el-table v-else-if="tab === 'refusals'" v-loading="loading" :data="refusals">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="用户" width="150">
          <template #default="{ row }">{{ row.userId }}</template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.refusalType === 'OFFER_DISMISS' ? 'info' : 'danger'" effect="plain">
              {{ refusalTypeLabel(row.refusalType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="原因" width="130">
          <template #default="{ row }">{{ reasonLabel(row.reasonCode) }}</template>
        </el-table-column>
        <el-table-column prop="reasonText" label="补充说明" min-width="160" show-overflow-tooltip />
        <el-table-column label="策略效果" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ effectText(row.effect) }}</span>
          </template>
        </el-table-column>
        <template #empty><div class="empty">暂无拒绝记录</div></template>
      </el-table>

      <el-table v-else-if="tab === 'penalties'" v-loading="loading" :data="penalties">
        <el-table-column label="用户" width="150">
          <template #default="{ row }">{{ row.userId }}</template>
        </el-table-column>
        <el-table-column label="惩罚" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.penaltyType === 'BLOCK' ? 'danger' : 'warning'">
              {{ row.penaltyType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="系数" width="80">
          <template #default="{ row }">{{ row.value ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="生效至" width="170">
          <template #default="{ row }">{{ fmt(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.revokedAt" type="info" size="small">已撤销</el-tag>
            <el-tag v-else-if="expired(row.expiresAt)" type="info" size="small" effect="plain">已过期</el-tag>
            <el-tag v-else type="danger" size="small" effect="plain">生效中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canRevoke && !row.revokedAt" size="small" @click="revoke(row)">撤销</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="empty">暂无惩罚记录</div></template>
      </el-table>

      <el-table v-else v-loading="loading" :data="ratings">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="被评价人" width="150">
          <template #default="{ row }">{{ row.targetUserId }}</template>
        </el-table-column>
        <el-table-column label="评分" width="150">
          <template #default="{ row }"><el-rate :model-value="row.score" disabled /></template>
        </el-table-column>
        <el-table-column prop="comment" label="评价" min-width="200" show-overflow-tooltip />
        <el-table-column label="评价人" width="140">
          <template #default="{ row }">{{ row.raterId || '—' }}</template>
        </el-table-column>
        <template #empty><div class="empty">暂无评价记录</div></template>
      </el-table>
    </div>

    <el-dialog v-model="detailOpen" title="分单决策明细" width="880px">
      <div class="detail-task">
        <div class="detail-task-main">
          <div class="detail-task-title">{{ detail.taskName || detail.nodeId || '未知任务' }}</div>
          <div class="detail-task-meta">
            <span v-if="detail.businessKey">业务单号：<b class="mono">{{ detail.businessKey }}</b></span>
            <span v-if="detail.processKey">流程：<b class="mono">{{ detail.processKey }}</b></span>
            <span>任务 ID：<b class="mono">{{ detail.taskId || '—' }}</b></span>
            <span>实例 ID：<b class="mono">{{ detail.instanceId || '—' }}</b></span>
          </div>
        </div>
        <el-button v-if="detail.instanceId" size="small" @click="openInstance(detail.instanceId)">
          查看流程 →
        </el-button>
      </div>
      <div class="detail-summary">
        <div class="sum-item">
          <span class="sum-label">决策结果</span>
          <el-tag :type="outcomeType(detail.outcome)" size="small" effect="dark">{{ detail.outcome || '—' }}</el-tag>
        </div>
        <div class="sum-item"><span class="sum-label">算法</span><b>{{ detail.algorithm || '—' }}</b></div>
        <div class="sum-item">
          <span class="sum-label">最终派给</span>
          <b>{{ chosenName(detail) || detail.chosenUserId || '—' }}</b>
        </div>
        <div class="sum-item"><span class="sum-label">决策时间</span><b>{{ fmt(detail.createdAt) }}</b></div>
        <div class="sum-item sum-wide">
          <span class="sum-label">说明</span><span>{{ detail.reason || '—' }}</span>
        </div>
      </div>
      <div class="detail-block">
        <div class="detail-title">
          候选人评估
          <span class="detail-count">共 {{ detailRows.length }} 人 · 通过 {{ passedCount }} 人</span>
        </div>
        <el-table :data="detailRows" size="small" border max-height="380" :row-class-name="detailRowClass">
          <el-table-column label="候选人" min-width="180">
            <template #default="{ row }">
              <b>{{ row.name || row.userId }}</b>
              <span class="code-chip" style="margin-left: 6px">{{ row.userId }}</span>
              <el-tag v-if="row.userId === detail.chosenUserId" type="success" size="small"
                      effect="dark" style="margin-left: 6px">已派</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="88">
            <template #default="{ row }">
              <el-tag :type="row.passed ? 'success' : 'danger'" size="small" effect="plain">
                {{ row.passed ? '通过' : '淘汰' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="得分" width="86">
            <template #default="{ row }">
              <b v-if="row.passed" class="detail-score">{{ fmtScore(row.score) }}</b>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="签到" width="92">
            <template #default="{ row }">
              <el-tag :type="row.checkedIn ? 'success' : 'info'" size="small" effect="plain">
                {{ row.checkedIn ? '已签到' : '未签到' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="在办/容量" width="100">
            <template #default="{ row }">{{ row.active }} / {{ row.capacity }}</template>
          </el-table-column>
          <el-table-column label="评分" width="70">
            <template #default="{ row }">{{ row.rating }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="200">
            <template #default="{ row }">
              <span v-if="row.reason" class="detail-reason">{{ row.reason }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <template #empty><div class="empty">这次派单没有候选人快照</div></template>
        </el-table>
        <div class="detail-hint">
          淘汰原因来自过滤器（如签到、冷却、容量、在线状态）；得分由算法加权计算，仅通过过滤的人参与排名。
        </div>
      </div>
    </el-dialog>

    <InstanceDetailDrawer v-model="instanceOpen" :instance-id="activeInstanceId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { dispatchApi } from '../api/workflowDispatch'
import { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'
import InstanceDetailDrawer from '../components/InstanceDetailDrawer.vue'

const auth = useAuthStore()
const canManage = computed(() => auth.has('wf:manage') || auth.isSuperAdmin)
const canRevoke = computed(() => auth.has('wf:dispatch:revoke') || auth.isSuperAdmin)

const tab = ref<'logs' | 'refusals' | 'penalties' | 'ratings'>('logs')
const loading = ref(false)
const scanning = ref(false)
const poolId = ref<number | null>(null)
const pools = ref<any[]>([])
const stats = ref<any>({})
const logs = ref<any[]>([])
const mergeLogs = ref(true)
const expandedLogs = ref<string[]>([])
const logKeyword = ref('')
const logOutcome = ref('')
const logPage = ref(1)
const logSize = ref(20)
const logTotal = ref(0)
const outcomeOptions = [
  { value: 'ASSIGNED', label: 'ASSIGNED 已派' },
  { value: 'POOLED', label: 'POOLED 进池' },
  { value: 'QUEUED', label: 'QUEUED 待派' },
  { value: 'CLAIMED', label: 'CLAIMED 已认领' },
  { value: 'REQUEUED', label: 'REQUEUED 回池' },
  { value: 'FAILED', label: 'FAILED 失败' },
  { value: 'TIMEOUT', label: 'TIMEOUT 超时' }
]
const refusals = ref<any[]>([])
const penalties = ref<any[]>([])
const ratings = ref<any[]>([])

const detailOpen = ref(false)
const detail = ref<any>({})
const instanceOpen = ref(false)
const activeInstanceId = ref('')

function openInstance(instanceId: string) {
  activeInstanceId.value = instanceId
  instanceOpen.value = true
}

function fmt(value: any) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}

function waitText(seconds: any) {
  const value = Number(seconds || 0)
  if (!value) return '—'
  if (value < 60) return `${value} 秒`
  if (value < 3600) return `${Math.round(value / 60)} 分钟`
  return `${(value / 3600).toFixed(1)} 小时`
}

function outcomeType(outcome: string) {
  if (outcome === 'ASSIGNED') return 'success'
  if (outcome === 'QUEUED' || outcome === 'FAILED') return 'danger'
  if (outcome === 'POOLED') return 'warning'
  return 'info'
}

function refusalTypeLabel(type: string) {
  return { OFFER_DISMISS: '忽略推荐', ASSIGN_REFUSAL: '拒绝指派', RELEASE: '退回池' }[type] || type
}

function reasonLabel(code: string) {
  return {
    BUSY: '任务过多', SKILL_MISMATCH: '技能不匹配', OUT_OF_SCOPE: '不在职责范围',
    CONFLICT: '利益冲突', OTHER: '其他'
  }[code] || code
}

function effectText(raw: any) {
  if (!raw) return '—'
  try {
    const effect = typeof raw === 'string' ? JSON.parse(raw) : raw
    const applied = Array.isArray(effect.applied) ? effect.applied.join(', ') : ''
    return applied || (effect.escalate ? '触发升级' : '仅记录')
  } catch {
    return String(raw).slice(0, 80)
  }
}

function expired(value: any) {
  return value ? new Date(value).getTime() < Date.now() : false
}

// 决策日志只存 chosenUserId（数字 ID），候选人快照里有 userId→name 映射，用它显示人名。
function chosenName(row: any): string {
  const id = row?.chosenUserId
  if (!id) return ''
  const hit = asArray(row?.candidates).find((c: any) => String(c?.userId) === String(id))
  return hit?.name || ''
}

function asArray(value: any): any[] {
  if (Array.isArray(value)) return value
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return Array.isArray(parsed) ? parsed : []
    } catch {
      return []
    }
  }
  return []
}

function asObject(value: any): Record<string, any> {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

// 决策明细转成人类可读的候选行：候选人快照给出姓名/签到/在办/评分/淘汰原因，
// scores 补上打分。按「通过优先、分数倒序」排列，和派单排名口径一致。
const detailRows = computed(() => {
  const scores = asObject(detail.value?.scores)
  return asArray(detail.value?.candidates).map((row: any) => ({
    userId: row?.userId ?? '—',
    name: row?.name,
    passed: row?.passed === true,
    score: Number(scores[row?.userId] ?? row?.score ?? 0),
    reason: row?.reason,
    active: row?.active ?? 0,
    capacity: row?.capacity ?? 0,
    checkedIn: row?.checkedIn === true,
    rating: row?.rating ?? 0
  })).sort((a, b) => {
    if (a.passed !== b.passed) return a.passed ? -1 : 1
    return b.score - a.score
  })
})

const passedCount = computed(() => detailRows.value.filter((row) => row.passed).length)

function fmtScore(value: number) {
  return Number.isFinite(value) ? value.toFixed(3) : String(value)
}

function detailRowClass({ row }: any) {
  return row.passed ? 'candidate-pass' : 'candidate-out'
}

// 重派失败会连续写多条「同一单子 + 同一结果 + 同一原因」的日志，
// 折叠成一行展示，避免刷屏。只在「连续」相同时合并（中间出现别的决策就断开）。
function logRowKey(row: any) {
  return row.group[0]?.id ?? Math.random().toString(36).slice(2)
}

function onLogExpand(row: any, expanded: boolean) {
  const key = logRowKey(row)
  if (expanded) {
    if (!expandedLogs.value.includes(key)) expandedLogs.value = [...expandedLogs.value, key]
  } else {
    expandedLogs.value = expandedLogs.value.filter((k) => k !== key)
  }
}

const displayLogs = computed(() => {
  const rows = logs.value
  if (!mergeLogs.value) {
    return rows.map((log) => ({ ...log, group: [log] }))
  }
  const result: any[] = []
  for (const log of rows) {
    const prev = result[result.length - 1]
    if (prev && prev.taskId === log.taskId && prev.outcome === log.outcome && prev.reason === log.reason) {
      prev.group.push(log)
    } else {
      result.push({ ...log, group: [log] })
    }
  }
  return result
})

async function loadPools() {
  try {
    pools.value = await dispatchApi.pools()
  } catch (error) {
    console.error('[DispatchBoard] 分单池加载失败', error)
    pools.value = []
  }
}

async function loadStats() {
  try {
    stats.value = await dispatchApi.stats(poolId.value || undefined)
  } catch (error) {
    console.error('[DispatchBoard] 统计加载失败', error)
    stats.value = {}
  }
}

async function loadLogs() {
  try {
    const res = await dispatchApi.logs({
      poolId: poolId.value || undefined,
      keyword: logKeyword.value.trim() || undefined,
      outcome: logOutcome.value || undefined,
      page: logPage.value,
      size: logSize.value
    })
    logs.value = res?.rows || []
    logTotal.value = Number(res?.total || 0)
    expandedLogs.value = []
  } catch (error) {
    console.error('[DispatchBoard] 决策日志加载失败', error)
    logs.value = []
    logTotal.value = 0
  }
}

function searchLogs() {
  logPage.value = 1
  loadLogs()
}

function resetLogFilter() {
  logKeyword.value = ''
  logOutcome.value = ''
  searchLogs()
}

function onLogSizeChange() {
  logPage.value = 1
  loadLogs()
}

function onPoolChange() {
  logPage.value = 1
  loadStats()
  loadCurrentTab()
}

async function loadRefusals() {
  try {
    refusals.value = await dispatchApi.refusals({ poolId: poolId.value || undefined, limit: 100 })
  } catch (error) {
    console.error('[DispatchBoard] 拒绝记录加载失败', error)
    refusals.value = []
  }
}

async function loadPenalties() {
  try {
    penalties.value = await dispatchApi.penalties({ limit: 100 })
  } catch (error) {
    console.error('[DispatchBoard] 惩罚记录加载失败', error)
    penalties.value = []
  }
}

async function loadRatings() {
  try {
    ratings.value = await dispatchApi.ratings({ limit: 100 })
  } catch (error) {
    console.error('[DispatchBoard] 评价记录加载失败', error)
    ratings.value = []
  }
}

async function refreshAll() {
  loading.value = true
  try {
    await Promise.allSettled([loadStats(), loadCurrentTab()])
  } finally {
    loading.value = false
  }
}

function loadCurrentTab() {
  if (tab.value === 'logs') return loadLogs()
  if (tab.value === 'refusals') return loadRefusals()
  if (tab.value === 'penalties') return loadPenalties()
  return loadRatings()
}

async function onTabChange() {
  loading.value = true
  try {
    await loadCurrentTab()
  } finally {
    loading.value = false
  }
}

function openDetail(row: any) {
  detail.value = row
  detailOpen.value = true
}

async function revoke(row: any) {
  try {
    await ElMessageBox.confirm(`撤销「${row.userId}」的 ${row.penaltyType} 惩罚？撤销后立即恢复派单权重。`,
      '撤销惩罚', { type: 'warning' })
  } catch {
    return
  }
  try {
    await dispatchApi.revokePenalty(row.id)
    ElMessage.success('已撤销')
    await Promise.allSettled([loadPenalties(), loadStats()])
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function runTimeoutScan() {
  scanning.value = true
  try {
    const result = await dispatchApi.timeoutScan()
    ElMessage.success(`扫描完成，处理 ${result?.released ?? 0} 个任务`)
    await refreshAll()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    scanning.value = false
  }
}

onMounted(async () => {
  await Promise.allSettled([loadPools(), refreshAll()])
})
</script>

<style scoped>
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; margin: 18px 0; }
.stat-card { background: #fff; border: 1px solid #DCE7E1; border-radius: 12px; padding: 16px 18px; }
.stat-label { color: var(--bp-muted); font-size: 12.5px; }
.stat-value { font-size: 24px; font-weight: 700; margin-top: 6px; letter-spacing: .4px; }
.empty { padding: 30px 0; color: var(--bp-muted); }
.logs-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.logs-toolbar .hint { color: var(--bp-muted); font-size: 12px; }
.logs-pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.spacer { flex: 1; }
.repeat-count { color: #B04A4A; font-weight: 700; font-size: 12px; margin-left: 4px; }
.log-repeat-hint { color: var(--bp-muted); font-size: 11px; margin-top: 2px; }
.log-group { padding: 4px 12px 8px 44px; background: #F8FAF9; }
.log-group-head { color: var(--bp-muted); font-size: 12px; margin-bottom: 6px; }
.log-group-row { display: flex; align-items: center; gap: 12px; padding: 4px 0;
  border-bottom: 1px dashed #E6EDE9; font-size: 12.5px; }
.log-group-row:last-child { border-bottom: none; }
.log-group-reason { color: var(--bp-muted); flex: 1; }
.log-group-single { color: var(--bp-muted); font-size: 12px; }
.task-cell { display: flex; flex-direction: column; gap: 2px; }
.task-name { font-weight: 600; font-size: 13px; }
.task-biz { color: var(--bp-primary, #0F7B5A); font-size: 12px; }
.detail-task { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
  padding: 14px 16px; background: linear-gradient(135deg, #F0F7F4, #F8FBF9);
  border: 1px solid #D8E9E1; border-radius: 10px; margin-bottom: 14px; }
.detail-task-title { font-size: 16px; font-weight: 700; margin-bottom: 6px; }
.detail-task-meta { display: flex; flex-wrap: wrap; gap: 6px 18px; color: var(--bp-muted); font-size: 12px; }
.detail-summary { display: flex; flex-wrap: wrap; gap: 10px 24px; padding: 12px 16px;
  background: #F6F9F7; border: 1px solid #E4EDE8; border-radius: 10px; margin-bottom: 16px; }
.sum-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.sum-item.sum-wide { flex-basis: 100%; }
.sum-label { color: var(--bp-muted); font-size: 12.5px; }
.detail-block + .detail-block { margin-top: 14px; }
.detail-title { font-size: 13px; font-weight: 600; margin-bottom: 10px; }
.detail-count { color: var(--bp-muted); font-weight: 400; font-size: 12px; margin-left: 8px; }
.detail-score { color: var(--bp-primary, #0F7B5A); font-variant-numeric: tabular-nums; }
.detail-reason { color: #B04A4A; }
.muted { color: var(--bp-muted); }
.detail-hint { color: var(--bp-muted); font-size: 12px; margin-top: 10px; line-height: 1.6; }
:deep(.candidate-out) { background: #FBF6F6; }
:deep(.candidate-pass) { background: #F4FAF7; }
</style>
