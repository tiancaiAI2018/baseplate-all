<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">工作流</h2>
        <p class="page-desc">
          设计器画图部署 → 发起实例 → 待办办理 → 实例监控。作废只挡新实例，老实例照常走完；
          部署同名新版本时可以选择「保持旧版」或「全部迁移」。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" type="border-card">
      <el-tab-pane label="流程设计" name="design">
        <div class="toolbar">
          <el-input v-model="processKey" placeholder="流程 Key" style="width: 220px" />
          <el-button :icon="Grid" :loading="layouting" @click="autoLayout">自动排版</el-button>
          <el-button type="primary" @click="openDeploy">部署当前图</el-button>
          <span class="hint">同名 Key 会生成新版本；部署时可决定老实例是否一起迁移。</span>
        </div>
        <el-row :gutter="14">
          <el-col :span="15"><div class="wf-canvas" ref="canvasRef"></div></el-col>
          <el-col :span="9"><NodeConfigPanel ref="panelRef" :modeler="designerModel" /></el-col>
        </el-row>
        <div class="wf-step" style="margin-top: 16px">
          <div class="no">1</div>
          <div class="body"><b>画流程</b><p>左侧节点拖到画布，连线到结束节点；双击节点可改名。示例图已带审批结果网关，驳回会回到「发起人重做」。</p></div>
        </div>
        <div class="wf-step">
          <div class="no">2</div>
          <div class="body"><b>设置办理人</b><p>主管审批用 <span class="code-chip">${assignee}</span>（发起时选择），发起人重做用 <span class="code-chip">${initiator}</span>（后端自动注入当前登录人）。</p></div>
        </div>
        <div class="wf-step">
          <div class="no">3</div>
          <div class="body"><b>部署</b><p>点「部署当前图」，选择版本变更策略；后端会做 BPMN 白名单校验并写入 Flowable。</p></div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="流程定义" name="definitions">
        <div class="toolbar">
          <span class="hint">按流程 Key 汇总；点「详情」看流程图、全部版本与实例占用情况。</span>
          <div class="spacer"></div>
          <el-button size="small" :icon="Refresh" @click="loadDefinitions">刷新</el-button>
        </div>
        <el-table :data="definitionRows" size="small">
          <el-table-column label="流程 Key" width="180">
            <template #default="{ row }"><span class="code-chip">{{ row.key }}</span></template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column label="最新版本" width="100">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="版本数" width="90">
            <template #default="{ row }">{{ row.versionCount }}</template>
          </el-table-column>
          <el-table-column label="运行中实例" width="120">
            <template #default="{ row }">
              <span :class="row.runningInstances ? 'num-warn' : ''">{{ row.runningInstances }}</span>
            </template>
          </el-table-column>
          <el-table-column label="已结束" width="90">
            <template #default="{ row }">{{ row.finishedInstances }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.suspended ? 'danger' : 'success'" size="small">
                {{ row.suspended ? '已作废' : '启用中' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="auth.has('wf:config')" label="发起范围" width="130">
            <template #default="{ row }">
              <span class="pill" :class="scopePillClass(row.key)">{{ scopeText(row.key) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDefinition(row)">详情</el-button>
              <el-button link type="primary" size="small" @click="startFrom(row)">发起</el-button>
              <el-button v-if="auth.has('wf:config')" link type="primary" size="small"
                         @click="openSetting(row)">发起配置</el-button>
              <el-button link :type="row.suspended ? 'success' : 'danger'" size="small"
                         @click="toggleSuspend(row)">
                {{ row.suspended ? '恢复启用' : '作废' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!definitionRows.length" description="还没有部署流程，请先在「流程设计」里部署" :image-size="80" />
      </el-tab-pane>

      <el-tab-pane label="发起流程" name="start">
        <el-row :gutter="18">
          <el-col :span="12">
            <div class="card" style="box-shadow: none">
              <h3 class="card-title"><span class="dot"></span>发起新实例</h3>
              <el-form label-width="110px">
                <el-form-item label="流程定义">
                  <el-select v-model="startForm.processKey" style="width: 100%" placeholder="选择已部署流程">
                    <el-option v-for="item in definitionRows" :key="item.key"
                               :label="`${item.name || item.key}（v${item.version}）`" :value="item.key" />
                  </el-select>
                </el-form-item>
                <el-form-item label="业务单号">
                  <el-input v-model="startForm.businessKey" placeholder="如 LEAVE-2026-001" />
                </el-form-item>
                <el-form-item label="办理人">
                  <el-select v-model="startForm.assignee" filterable style="width: 100%"
                             placeholder="选择谁来处理第一个任务">
                    <el-option v-for="user in users" :key="user.id"
                               :label="`${user.displayName || user.username}（ID ${user.id}）`" :value="String(user.id)" />
                  </el-select>
                </el-form-item>
                <el-form-item label="业务表单">
                  <FormRenderer ref="startRenderer" :rule="startFormRule" v-model="startFormData" />
                  <span v-if="!startFormRule?.length" class="hint">该流程还没绑定表单，可在「流程表单」里设计并在节点属性里绑定。</span>
                </el-form-item>
                <el-button type="primary" @click="startInstance">发起实例</el-button>
              </el-form>
              <p class="hint" style="margin-top: 12px">
                办理人是谁，谁登录后能在「待办办理」里看到；发起人由后端自动注入，驳回后会回到发起人重做。
              </p>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="card" style="box-shadow: none">
              <h3 class="card-title"><span class="dot"></span>测试用户</h3>
              <p class="hint">需要多个测试账号时，可以在「用户管理」里创建，然后回到这里选择办理人。</p>
              <el-table :data="users.slice(0, 8)" size="small">
                <el-table-column label="用户" min-width="150">
                  <template #default="{ row }">
                    <div style="display: flex; align-items: center; gap: 8px">
                      <div class="avatar" style="width: 24px; height: 24px; font-size: 11px">
                        {{ (row.displayName || row.username).slice(0, 1) }}
                      </div>
                      {{ row.displayName || row.username }}
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="角色" width="140">
                  <template #default="{ row }">{{ (row.roles || []).join(', ') || '—' }}</template>
                </el-table-column>
              </el-table>
            </div>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane label="实例监控" name="instances">
        <div class="toolbar">
          <el-select v-model="filter.state" style="width: 130px">
            <el-option label="全部状态" value="ALL" />
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已结束" value="FINISHED" />
          </el-select>
          <el-select v-model="filter.processKey" clearable placeholder="流程 Key" style="width: 180px">
            <el-option v-for="item in definitionRows" :key="item.key" :label="item.name || item.key" :value="item.key" />
          </el-select>
          <el-input v-model="filter.keyword" placeholder="业务单号包含" clearable style="width: 180px" />
          <el-button type="primary" @click="loadInstances">查询</el-button>
          <el-button :icon="Refresh" @click="loadInstances">刷新</el-button>
          <div class="spacer"></div>
          <span class="hint">共 {{ total }} 条；已结束实例同样能看完整轨迹与流程图。</span>
        </div>
        <el-table :data="instances" size="small">
          <el-table-column label="实例" width="130">
            <template #default="{ row }">
              <el-tooltip :content="row.id" placement="top"><span class="mono short-id">{{ shortId(row.id) }}</span></el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="processDefinitionName" label="流程" width="130" />
          <el-table-column label="版本" width="70">
            <template #default="{ row }">v{{ row.version || '—' }}</template>
          </el-table-column>
          <el-table-column prop="businessKey" label="业务单号" width="140" show-overflow-tooltip />
          <el-table-column label="当前任务" min-width="130">
            <template #default="{ row }">
              <el-tag v-for="task in row.currentTasks" :key="task" size="small" style="margin-right: 4px">{{ task }}</el-tag>
              <span v-if="!row.currentTasks?.length" class="hint">—</span>
            </template>
          </el-table-column>
          <el-table-column label="打回" width="80">
            <template #default="{ row }">
              <span v-if="row.rollbackCount" class="num-warn">{{ row.rollbackCount }} 次</span>
              <span v-else class="hint">0</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.ended ? 'info' : 'success'" size="small">{{ row.ended ? '已结束' : '运行中' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发起时间" width="150">
            <template #default="{ row }">{{ fmt(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openInstance(row)">查看详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top: 14px; justify-content: flex-end"
                       layout="total, sizes, prev, pager, next" :total="total"
                       v-model:current-page="page" v-model:page-size="size"
                       :page-sizes="[10, 20, 50]" @current-change="loadInstances" @size-change="loadInstances" />
      </el-tab-pane>

      <el-tab-pane label="待办办理" name="tasks">
        <div class="toolbar">
          <el-select v-model="taskAssignee" filterable style="width: 260px" placeholder="选择办理人">
            <el-option v-for="user in users" :key="user.id"
                       :label="`${user.displayName || user.username}（ID ${user.id}）`" :value="String(user.id)" />
          </el-select>
          <el-button @click="loadTasks">查看该用户待办</el-button>
          <el-button :icon="Refresh" @click="loadTasks">刷新</el-button>
          <span class="hint">驳回时必须填写原因，管理员会在实例监控里看到原因与累计打回次数。</span>
        </div>
        <el-table :data="tasks" size="small">
          <el-table-column prop="name" label="任务" width="160" />
          <el-table-column label="办理人" width="150">
            <template #default="{ row }">{{ userName(row.assignee) }}</template>
          </el-table-column>
          <el-table-column label="所属实例" min-width="200">
            <template #default="{ row }"><span class="mono">{{ row.processInstanceId }}</span></template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="320">
            <template #default="{ row }">
              <el-button size="small" type="success" plain @click="complete(row, 'APPROVE')">通过</el-button>
              <el-button size="small" type="danger" plain @click="complete(row, 'REJECT')">驳回</el-button>
              <el-button size="small" @click="rollback(row)">回退</el-button>
              <el-button size="small" @click="transfer(row)">转办</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!tasks.length" description="该用户当前没有待办；确认任务办理人是否正确" :image-size="80" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="deployOpen" title="部署新版本 · 选择变更策略" width="620px">
      <div class="box" style="margin-bottom: 14px">
        <span class="lbl">当前状态</span>
        流程 <b>{{ processKey }}</b> 已有 <b>{{ existingVersions }}</b> 个版本、
        <b>{{ existingRunning }}</b> 个运行中的实例。
      </div>
      <el-radio-group v-model="deployStrategy" class="wf-strategy">
        <el-radio value="KEEP_OLD">
          <b>保持原有流转</b>
          <div class="hint">老实例继续挂在旧版本上按原流程走完，新发起的实例自动走新版本。风险最低。</div>
        </el-radio>
        <el-radio value="MIGRATE_ALL">
          <b>全部迁移到新版本</b>
          <div class="hint">运行中的实例改挂新版本，当前停在哪个节点就还停在哪个节点。若新版本删掉了某个在用的节点，整体拒绝并给出报告。</div>
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="deployOpen = false">取消</el-button>
        <el-button type="primary" :loading="deploying" @click="confirmDeploy">确认部署</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="defOpen" size="900px" title="流程定义详情">
      <template v-if="defDetail">
        <div class="detail-hero">
          <h3>{{ defDetail.name || defDetail.key }} <span class="hint">v{{ defDetail.version }}</span></h3>
          <div class="sub"><span class="code-chip">{{ defDetail.key }}</span> · 部署于 {{ fmt(defDetail.deployedAt) }}</div>
        </div>
        <div class="wf-stats">
          <div><b>{{ rowSummary(defDetail)?.runningInstances ?? defDetail.runningInstances }}</b><span>运行中实例（全部版本）</span></div>
          <div><b>{{ rowSummary(defDetail)?.finishedInstances ?? defDetail.finishedInstances }}</b><span>已结束实例（全部版本）</span></div>
          <div><b>{{ defDetail.versions.length }}</b><span>历史版本</span></div>
          <div><b>{{ defDetail.suspended ? '已作废' : '启用中' }}</b><span>当前版本状态</span></div>
        </div>
        <div class="toolbar" style="margin-top: 14px">
          <el-select v-model="defVersionId" style="width: 280px" @change="loadDefXml">
            <el-option v-for="item in defDetail.versions" :key="item.id"
                       :label="`v${item.version} · ${item.runningInstances} 运行中${item.suspended ? ' · 已作废' : ''}`"
                       :value="item.id" />
          </el-select>
          <div class="spacer"></div>
          <el-button :type="defDetail.suspended ? 'success' : 'danger'" plain
                     @click="toggleSuspend(defDetail)">
            {{ defDetail.suspended ? '恢复启用' : '作废此版本' }}
          </el-button>
          <el-button type="primary" plain @click="editDefinition">编辑此流程</el-button>
        </div>
        <div class="wf-chart" ref="defChartRef"></div>
        <p class="hint" style="margin: 8px 0 16px">
          作废只影响新实例：已经发起的实例不会被挂起，仍可按原流程走完。变更流程请在「流程设计」里部署同名新版本并选择策略。
        </p>
        <h4 style="margin: 16px 0 6px">节点配置（这个流程每个节点到底配了什么）</h4>
        <el-table :data="defNodeConfigs" size="small" max-height="240">
          <el-table-column prop="nodeName" label="节点" width="140" />
          <el-table-column label="找人逻辑 / 出线条件" width="220">
            <template #default="{ row }">
              <!-- 连线节点没有"找人和签署"，它配的是条件；混在一起显示会读成"发起人单签"，纯误导 -->
              <span v-if="row.nodeType === 'sequenceFlow'" class="code-chip">{{ conditionLabel(row) }}</span>
              <template v-else>{{ assigneeLabel(row) }}</template>
            </template>
          </el-table-column>
          <el-table-column label="签署方式" width="130">
            <template #default="{ row }">
              <span v-if="row.nodeType === 'sequenceFlow'" class="hint">—</span>
              <template v-else>{{ signLabel(row.signType) }}<span v-if="row.signRatio < 100"> · {{ row.signRatio }}%</span></template>
            </template>
          </el-table-column>
          <el-table-column label="绑定表单" width="150">
            <template #default="{ row }">
              <span v-if="row.formCode">{{ row.formCode }}</span>
              <span v-else class="hint">—</span>
            </template>
          </el-table-column>
          <el-table-column label="决策按钮" min-width="180">
            <template #default="{ row }">
              <el-tag v-for="b in (row.decisionConfig?.buttons || [])" :key="b.code" size="small" effect="plain"
                      style="margin: 2px 4px 2px 0">
                {{ b.label || b.code }}{{ b.reasonRequired ? ' · 必填原因' : '' }}
              </el-tag>
              <span v-if="!(row.decisionConfig?.buttons || []).length" class="hint">—</span>
            </template>
          </el-table-column>
        </el-table>
        <h4 style="margin: 10px 0 6px">版本历史</h4>
        <el-table :data="defDetail.versions" size="small">
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column prop="id" label="定义 ID" min-width="220" show-overflow-tooltip />
          <el-table-column label="部署时间" width="160">
            <template #default="{ row }">{{ fmt(row.deployedAt) }}</template>
          </el-table-column>
          <el-table-column label="运行中 / 已结束" width="150">
            <template #default="{ row }">{{ row.runningInstances }} / {{ row.finishedInstances }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.suspended ? 'danger' : 'success'" size="small">{{ row.suspended ? '已作废' : '启用中' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <el-drawer v-model="instOpen" size="1000px" title="流程实例详情">
      <template v-if="instDetail">
        <div class="detail-hero">
          <h3>{{ instDetail.processDefinitionName || instDetail.processDefinitionKey }} <span class="hint">v{{ instDetail.version }}</span></h3>
          <div class="sub">业务单号 {{ instDetail.businessKey || '—' }} · 实例 <span class="mono">{{ instDetail.id }}</span></div>
        </div>
        <div v-if="instDetail.rollbackCount" class="wf-alert">
          <b>该流程已被打回 {{ instDetail.rollbackCount }} 次</b>
          <span>最近原因：{{ instDetail.lastReason || '未填写' }}<template v-if="instDetail.lastDecision">（{{ instDetail.lastDecision }}）</template></span>
        </div>
        <dl class="kv">
          <dt>状态</dt><dd>{{ instDetail.ended ? '已结束' : '运行中' }} · {{ fmt(instDetail.startedAt) }} → {{ fmt(instDetail.endedAt) }}</dd>
          <dt>耗时</dt><dd>{{ instDetail.durationMs != null ? Math.round(instDetail.durationMs / 1000) + ' 秒' : '—' }}</dd>
          <dt>发起人</dt><dd>{{ userName(instDetail.startUserId) }}</dd>
          <dt>结束原因</dt><dd>{{ instDetail.deleteReason || '—' }}</dd>
          <dt>最近打回原因</dt><dd>{{ instDetail.lastReason || '—' }}</dd>
        </dl>
        <div class="toolbar" style="margin-top: 8px">
          <span class="hint">
            <span class="legend done"></span>已走过
            <span class="legend current"></span>当前节点
            <span class="legend rollback"></span>被打回过的节点
          </span>
          <div class="spacer"></div>
          <el-button v-if="!instDetail.ended" type="danger" plain size="small" @click="terminate">终止实例</el-button>
        </div>
        <div class="wf-chart" ref="instChartRef"></div>
        <h4 style="margin: 18px 0 6px">任务轨迹（含已结束任务）</h4>
        <el-table :data="instDetail.tasks" size="small" max-height="260">
          <el-table-column prop="name" label="任务" width="150" />
          <el-table-column label="办理人" width="140">
            <template #default="{ row }">{{ userName(row.assignee) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.decision" :type="row.decision === 'REJECT' ? 'danger' : 'success'" size="small">{{ row.decision }}</el-tag>
              <span v-else class="hint">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因 / 意见" min-width="180" show-overflow-tooltip />
          <el-table-column label="开始" width="150"><template #default="{ row }">{{ fmt(row.startedAt) }}</template></el-table-column>
          <el-table-column label="结束" width="150"><template #default="{ row }">{{ fmt(row.endedAt) }}</template></el-table-column>
        </el-table>
        <el-empty v-if="!instDetail.tasks.length" description="没有任务轨迹" :image-size="60" />
        <h4 style="margin: 18px 0 6px">操作流水</h4>
        <el-table :data="instDetail.operations" size="small" max-height="220">
          <el-table-column prop="action" label="动作" width="120" />
          <el-table-column prop="operator" label="操作人" width="120" />
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="时间" width="160"><template #default="{ row }">{{ fmt(row.occurredAt) }}</template></el-table-column>
        </el-table>
        <h4 style="margin: 18px 0 6px">业务变量</h4>
        <pre class="json">{{ pretty(instDetail.variables) }}</pre>
      </template>
    </el-drawer>

    <ProcessSettingDialog v-model="settingOpen" :process-key="settingKey" :process-name="settingName"
                          @saved="loadSettings" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import BpmnViewer from 'bpmn-js/lib/Viewer'
import NodeConfigPanel from './NodeConfigPanel.vue'
import FormRenderer from './FormRenderer.vue'
import ProcessSettingDialog from '../components/ProcessSettingDialog.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Grid } from '@element-plus/icons-vue'
import { layoutBpmnXml } from '../utils/bpmnLayout'
import { bpmnToSpec } from '../utils/bpmnSpec'
import { registerAgentCanvas } from '../api/agentCanvas'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const activeTab = ref('design')
const canvasRef = ref<HTMLElement | null>(null)
const defChartRef = ref<HTMLElement | null>(null)
const instChartRef = ref<HTMLElement | null>(null)
const processKey = ref('leave-approval')
const definitions = ref<any[]>([])
const users = ref<any[]>([])
const instances = ref<any[]>([])
const tasks = ref<any[]>([])
const taskAssignee = ref('')
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = reactive({ state: 'ALL', processKey: '', keyword: '' })
let modeler: any = null
let disposeCanvasProvider: (() => void) | null = null
// 必须是 shallowRef：ref() 会把 bpmn-js 实例包成 reactive 代理，
// 右侧节点面板拿到的就是代理对象，engine.get('elementRegistry') 内部身份判断失败 → 节点列表永远空
const designerModel = shallowRef<any>(null)
const panelRef = ref<any>(null)
const startRenderer = ref<any>(null)
const startFormRule = ref<any[]>([])
const startFormData = ref<Record<string, any>>({})
const startFormCode = ref('')
const defNodeConfigs = ref<any[]>([])
let defViewer: any = null
let instViewer: any = null

const deployOpen = ref(false)
const deployStrategy = ref('KEEP_OLD')
const deploying = ref(false)
const layouting = ref(false)
const defOpen = ref(false)
const defDetail = ref<any>(null)
const defVersionId = ref('')
const instOpen = ref(false)
const instDetail = ref<any>(null)

const startForm = reactive({ processKey: '', businessKey: '', assignee: '', variables: '{}' })
const nodeOptions = ref<any>({})

const settingOpen = ref(false)
const settingKey = ref('')
const settingName = ref('')
const processSettings = ref<Record<string, any>>({})

function openSetting(row: any) {
  settingKey.value = row.key
  settingName.value = row.name || row.key
  settingOpen.value = true
}

async function loadSettings() {
  if (!auth.has('wf:config')) return
  try {
    const { data } = await http.get('/api/workflow/process-settings')
    const map: Record<string, any> = {}
    for (const item of data || []) map[item.processKey] = item
    processSettings.value = map
  } catch { processSettings.value = {} }
}

const SCOPE_TEXT: Record<string, string> = { ALL: '所有人', ROLE: '指定角色', USER: '指定人员', ORG: '指定组织' }
function scopeText(key: string) {
  const setting = processSettings.value[key]
  if (!setting) return '所有人'
  if (!setting.enabled) return '已停用'
  return SCOPE_TEXT[setting.startScopeType] || '所有人'
}
function scopePillClass(key: string) {
  const setting = processSettings.value[key]
  if (setting && !setting.enabled) return 'orange'
  return !setting || setting.startScopeType === 'ALL' ? 'gray' : 'green'
}

const definitionRows = computed(() => {
  const groups = new Map<string, any[]>()
  for (const item of definitions.value) {
    if (!groups.has(item.key)) groups.set(item.key, [])
    groups.get(item.key)!.push(item)
  }
  return Array.from(groups.entries()).map(([key, list]) => {
    const latest = [...list].sort((a, b) => b.version - a.version)[0]
    return {
      ...latest,
      versionCount: list.length,
      runningInstances: list.reduce((sum, item) => sum + (item.runningInstances || 0), 0),
      finishedInstances: list.reduce((sum, item) => sum + (item.finishedInstances || 0), 0)
    }
  })
})

const existingVersions = computed(() => definitionRows.value.find((item) => item.key === processKey.value)?.versionCount || 0)
const existingRunning = computed(() => definitionRows.value.find((item) => item.key === processKey.value)?.runningInstances || 0)

const DEFAULT_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:flowable="http://flowable.org/bpmn"
                  id="Definitions_1" targetNamespace="http://baseplate">
  <bpmn:process id="leave-approval" name="请假审批" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="提交" />
    <bpmn:userTask id="Task_Approve" name="主管审批" flowable:assignee="\${assignee}" />
    <bpmn:exclusiveGateway id="Gateway_Decision" name="审批结果" />
    <bpmn:userTask id="Task_Rework" name="发起人重做" flowable:assignee="\${initiator}" />
    <bpmn:endEvent id="End_Approved" name="审批通过" />
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Approve" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Approve" targetRef="Gateway_Decision" />
    <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_Decision" targetRef="End_Approved" name="同意">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">\${decision == 'APPROVE'}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_4" sourceRef="Gateway_Decision" targetRef="Task_Rework" name="驳回">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">\${decision == 'REJECT'}</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_5" sourceRef="Task_Rework" targetRef="Task_Approve" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="leave-approval">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1"><dc:Bounds x="140" y="182" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_Approve_di" bpmnElement="Task_Approve"><dc:Bounds x="260" y="160" width="120" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Gateway_Decision_di" bpmnElement="Gateway_Decision"><dc:Bounds x="450" y="175" width="50" height="50" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_Rework_di" bpmnElement="Task_Rework"><dc:Bounds x="410" y="320" width="120" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="End_Approved_di" bpmnElement="End_Approved"><dc:Bounds x="580" y="182" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1_di" bpmnElement="Flow_1"><di:waypoint x="176" y="200" /><di:waypoint x="260" y="200" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_2_di" bpmnElement="Flow_2"><di:waypoint x="380" y="200" /><di:waypoint x="450" y="200" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_3_di" bpmnElement="Flow_3"><di:waypoint x="500" y="200" /><di:waypoint x="580" y="200" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_4_di" bpmnElement="Flow_4"><di:waypoint x="475" y="225" /><di:waypoint x="470" y="320" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_5_di" bpmnElement="Flow_5"><di:waypoint x="410" y="360" /><di:waypoint x="320" y="360" /><di:waypoint x="320" y="240" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`

function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
/** 定义详情的统计卡按整个流程 Key 汇总，避免误以为只有当前版本在用 */
function rowSummary(detail: any) { return definitionRows.value.find((item) => item.key === detail?.key) }
function shortId(id: string) { return id ? id.slice(0, 8) : '—' }
function pretty(value: any) { return JSON.stringify(value || {}, null, 2) }
function userName(id: any) {
  if (id == null) return '—'
  const user = users.value.find((item) => String(item.id) === String(id))
  return user ? `${user.displayName || user.username}（${id}）` : String(id)
}

async function renderChart(container: HTMLElement | null, xml: string, markers: Record<string, string[]>) {
  if (!container || !xml) return null
  container.innerHTML = ''
  const viewer = new BpmnViewer({ container })
  try {
    await viewer.importXML(xml)
    // 缩放单独兜住：抽屉还在做入场动画时容器宽度是 0，fit-viewport 会抛，
    // 但它不该连带把下面的 marker 打断（否则图在、高亮没了，看着像"丢了状态"）
    try { viewer.get('canvas').zoom('fit-viewport') } catch { /* 尺寸未就绪，保持默认缩放 */ }
    const registry = viewer.get('elementRegistry')
    const canvas = viewer.get('canvas')
    // 一个节点只显示一种状态：当前 > 被打回 > 已走过。
    // 同一个节点同时加两个 marker 会出现红框套橙框，文字也跟着被重复描边。
    const claimed = new Set<string>()
    for (const className of ['wf-current', 'wf-rollback', 'wf-done']) {
      for (const id of markers[className] || []) {
        if (claimed.has(id) || !registry.get(id)) continue
        canvas.addMarker(id, className)
        claimed.add(id)
      }
    }
  } catch (error) {
    ElMessage.warning('该流程缺少图形信息，暂时无法绘制流程图')
  }
  return viewer
}

function openDeploy() {
  deployStrategy.value = 'KEEP_OLD'
  deployOpen.value = true
}

async function confirmDeploy() {
  deploying.value = true
  try {
    const { xml } = await modeler.saveXML({ format: true })
    panelRef.value?.refreshNodes?.()
    const configs = panelRef.value?.getConfigs?.() || []
    const { data } = await http.post('/api/workflow/definitions', {
      bpmnXml: xml, processKey: processKey.value, strategy: deployStrategy.value,
      nodeConfigs: configs
    })
    deployOpen.value = false
    const report = data.migration
    if (report && !report.canMigrate) {
      await ElMessageBox.alert(
        `新版本已部署，但迁移被整体拒绝，老实例仍停留在原版本。\n\n原因：\n${(report.issues || []).join('\n')}`,
        '迁移未执行', { type: 'warning' })
    } else if (report) {
      ElMessage.success(`已部署 v${data.toDefinitionId ? '新版本' : ''}，${report.affectedInstances} 个运行中实例已保留当前节点迁移`)
    } else {
      ElMessage.success('已部署新版本；老实例保持原有流程流转')
    }
    await loadDefinitions()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    deploying.value = false
  }
}

async function loadDefinitions() {
  try {
    const { data } = await http.get('/api/workflow/definitions')
    definitions.value = data
    loadSettings()
    if (!Object.keys(nodeOptions.value).length) {
      http.get('/api/workflow/node-configs/options').then((res) => { nodeOptions.value = res.data }).catch(() => {})
    }
    if (!startForm.processKey && definitionRows.value.length) startForm.processKey = definitionRows.value[0].key
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function loadUsers() {
  try {
    const { data } = await http.get('/api/identity/users', { params: { page: 1, size: 200 } })
    users.value = data.items
    if (!taskAssignee.value) taskAssignee.value = auth.user?.userId || ''
    if (!startForm.assignee && data.items.length) startForm.assignee = String(data.items[0].id)
  } catch {
    users.value = []
  }
}

async function loadInstances() {
  try {
    const { data } = await http.get('/api/workflow/instances/page', {
      params: {
        page: page.value, size: size.value, state: filter.state,
        processKey: filter.processKey || undefined, keyword: filter.keyword || undefined
      }
    })
    instances.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function loadTasks() {
  try {
    const { data } = await http.get('/api/workflow/tasks', {
      params: { assignee: taskAssignee.value || undefined, page: 1, size: 50 }
    })
    tasks.value = data.items || []
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function openDefinition(row: any) {
  try {
    const { data } = await http.get(`/api/workflow/definitions/${row.id}`)
    defDetail.value = data
    defVersionId.value = data.id
    const cfg = await http.get('/api/workflow/node-configs',
      { params: { processKey: data.key, version: data.version } })
    defNodeConfigs.value = cfg.data || []
    defOpen.value = true
    await nextTick()
    await loadDefXml()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function assigneeLabel(row: any) {
  const found = (nodeOptions.value.assigneeTypes || [])
    .find((item: any) => item.value === row.assigneeType)
  return (found?.label || row.assigneeType || '—') + (row.assigneeValue ? `（${row.assigneeValue}）` : '')
}
function signLabel(type: string) {
  const found = (nodeOptions.value.signTypes || []).find((item: any) => item.value === type)
  return found?.label || type || '—'
}
const OPERATOR_TEXT: Record<string, string> = {
  '>': '大于', '>=': '大于等于', '<': '小于', '<=': '小于等于', '==': '等于', '!=': '不等于'
}
function conditionLabel(row: any) {
  const cfg = row.conditionConfig || {}
  if (cfg.expression) return cfg.expression
  if (!cfg.field) return '无条件（默认分支）'
  return `${cfg.field} ${OPERATOR_TEXT[cfg.operator] || cfg.operator} ${cfg.value}`
}

async function editDefinition() {
  if (!defDetail.value) return
  const { data } = await http.get(`/api/workflow/definitions/${defDetail.value.id}/xml`)
  processKey.value = defDetail.value.key
  // 必须先切到设计 tab 并等它拿到尺寸，否则画布宽高是 0，importXML 里的 fit-viewport 会抛异常
  defOpen.value = false
  activeTab.value = 'design'
  await nextTick()
  await waitCanvasSized(canvasRef.value)
  await modeler.importXML(data.xml)
  fitViewport()
  panelRef.value?.refreshNodes?.()
  panelRef.value?.loadConfigs?.(defNodeConfigs.value)
  ElMessage.success('已载入设计器；改完点「部署当前图」生成新版本')
}

async function loadStartForm() {
  if (!startForm.processKey) { startFormRule.value = []; startFormCode.value = ''; return }
  try {
    const cfg = await http.get('/api/workflow/node-configs',
      { params: { processKey: startForm.processKey } })
    const first = (cfg.data || []).find((item: any) => item.formCode)
    startFormCode.value = first?.formCode || ''
    if (!startFormCode.value) { startFormRule.value = []; return }
    const { data } = await http.get(`/api/metadata/FORM/${startFormCode.value}`)
    startFormRule.value = data.uiSchemaJson || '[]'
  } catch { startFormRule.value = [] }
}

async function loadDefXml() {
  if (!defVersionId.value) return
  try {
    const { data } = await http.get(`/api/workflow/definitions/${defVersionId.value}/xml`)
    if (defViewer) defViewer.destroy()
    defViewer = await renderChart(defChartRef.value, data.xml, {})
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function toggleSuspend(row: any) {
  const suspend = !row.suspended
  try {
    await ElMessageBox.confirm(
      suspend
        ? '作废后不能发起新实例；已经发起的实例不会被挂起，仍可按原流程走完。确定作废？'
        : '恢复后该版本可以重新发起新实例。确定恢复？',
      suspend ? '作废流程' : '恢复启用', { type: suspend ? 'warning' : 'info' })
    await http.post(`/api/workflow/definitions/${row.id}/${suspend ? 'suspend' : 'activate'}`)
    ElMessage.success(suspend ? '已作废；老实例不受影响' : '已恢复启用')
    await loadDefinitions()
    if (defOpen.value && defDetail.value) {
      const { data } = await http.get(`/api/workflow/definitions/${defDetail.value.id}`)
      defDetail.value = data
    }
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function openInstance(row: any) {
  try {
    const [{ data: detail }, { data: chart }] = await Promise.all([
      http.get(`/api/workflow/instances/${row.id}/detail`),
      http.get(`/api/workflow/instances/${row.id}/xml`)
    ])
    instDetail.value = detail
    instOpen.value = true
    await nextTick()
    if (instViewer) instViewer.destroy()
    instViewer = await renderChart(instChartRef.value, chart.xml, {
      'wf-done': chart.completedActivities || [],
      'wf-rollback': chart.rolledBackActivities || [],
      'wf-current': chart.currentActivities || []
    })
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function terminate() {
  try {
    const { value } = await ElMessageBox.prompt('填写终止原因（会写进操作流水）', '终止实例', { inputValue: '管理员终止' })
    await http.post(`/api/workflow/instances/${instDetail.value.id}/terminate`, { reason: value })
    ElMessage.success('已终止')
    await loadInstances()
    await openInstance({ id: instDetail.value.id })
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

function startFrom(row: any) {
  startForm.processKey = row.key
  activeTab.value = 'start'
}

async function startInstance() {
  try {
    if (!startForm.processKey || !startForm.assignee) {
      ElMessage.warning('请选择流程定义与办理人')
      return
    }
    const variables: any = { assignee: startForm.assignee }
    const values = startRenderer.value?.formData?.() || startFormData.value
    await http.post('/api/workflow/instances', {
      processKey: startForm.processKey,
      businessKey: startForm.businessKey || 'BIZ-' + Date.now(),
      formCode: startFormCode.value || undefined,
      formData: values,
      variables
    })
    ElMessage.success('已发起；切换到「待办办理」选择该用户即可看到任务')
    startForm.businessKey = ''
    await loadInstances()
  } catch (error: any) {
    ElMessage.error(errorText(error))
  }
}

async function complete(task: any, decision: string) {
  try {
    let reason = decision === 'APPROVE' ? '同意' : ''
    if (decision === 'REJECT') {
      const { value } = await ElMessageBox.prompt('请填写驳回原因，管理员会在实例监控里看到', '驳回', {
        inputPlaceholder: '如：材料不全，请补充', inputValidator: (v: string) => (v && v.trim() ? true : '驳回原因不能为空')
      })
      reason = value
    }
    await http.post(`/api/workflow/tasks/${task.taskId}/complete`, {
      decision, values: { reason, comment: reason }
    })
    ElMessage.success('已办理')
    await Promise.allSettled([loadTasks(), loadInstances()])
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function rollback(task: any) {
  try {
    const { value: node } = await ElMessageBox.prompt('目标节点 ID（可在流程图节点属性里查看）', '回退', { inputValue: 'Task_Approve' })
    const { value: reason } = await ElMessageBox.prompt('回退原因', '回退', { inputPlaceholder: '如：需要重新核实' })
    await http.post(`/api/workflow/tasks/${task.taskId}/rollback`, { targetNodeId: node, reason })
    ElMessage.success('已回退')
    await Promise.allSettled([loadTasks(), loadInstances()])
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function transfer(task: any) {
  try {
    const { value } = await ElMessageBox.prompt('转办给用户 ID（可在上方用户列表查看）', '转办')
    await http.post(`/api/workflow/tasks/${task.taskId}/transfer`, { toUserId: value })
    ElMessage.success('已转办')
    await loadTasks()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function refreshAll() {
  await Promise.allSettled([loadDefinitions(), loadUsers(), loadInstances(), loadTasks()])
}

// 容器还没被浏览器排版时（tab 刚切、动画未结束）尺寸是 0，
// bpmn-js 的 zoom('fit-viewport') 会算出非有限缩放比并抛异常，
// 异常会打断 importXML 后续流程 → 图空白 + 节点面板永远空。所以先等尺寸。
async function waitCanvasSized(el: HTMLElement | null, tries = 30) {
  for (let i = 0; i < tries; i += 1) {
    if (el && el.clientWidth > 20 && el.clientHeight > 20) return true
    await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
  }
  return false
}

function fitViewport() {
  try { modeler?.get('canvas').zoom('fit-viewport') } catch { /* 尺寸未就绪时忽略 */ }
}

/**
 * 自动排版：用 bpmn-auto-layout 重算节点坐标和连线走线。
 * 只改图形信息（DI），节点 id、属性、条件表达式都不变，
 * 所以右侧面板配置、部署内容都不受影响，等于流程图的「格式化」。
 */
async function autoLayout() {
  if (!modeler) return
  layouting.value = true
  try {
    const { xml } = await modeler.saveXML({ format: true })
    const layouted = layoutBpmnXml(xml || '')
    await modeler.importXML(layouted)
    fitViewport()
    panelRef.value?.refreshNodes?.()
    ElMessage.success('已自动排版，节点属性没有变化')
  } catch (error: any) {
    ElMessage.error(error?.message || '自动排版失败')
  } finally {
    layouting.value = false
  }
}

// 画布增删节点 / 改名后，右侧节点面板要跟着变，否则用户拖完节点看不到它
let nodeRefreshTimer: any = null
function bindCanvasEvents() {
  const eventBus = modeler?.get('eventBus')
  if (!eventBus) return
  const refresh = () => {
    clearTimeout(nodeRefreshTimer)
    nodeRefreshTimer = setTimeout(() => panelRef.value?.refreshNodes?.(), 120)
  }
  ;['shape.added', 'shape.removed', 'shape.changed', 'element.changed', 'import.done']
    .forEach((name) => eventBus.on(name, refresh))
}

onMounted(async () => {
  await nextTick()
  await waitCanvasSized(canvasRef.value)
  modeler = new BpmnModeler({ container: canvasRef.value })
  designerModel.value = modeler
  // AI 助手读取的是画布当前状态（含未保存修改），不是已部署版本
  disposeCanvasProvider = registerAgentCanvas('workflow/designer', async () => {
    if (!modeler) return { available: false, error: '画布尚未初始化' }
    const { xml } = await modeler.saveXML({ format: true })
    const spec = bpmnToSpec(modeler, processKey.value)
    return {
      available: true,
      data: {
        processKey: processKey.value,
        name: spec.name,
        bpmnXml: xml,
        spec
      }
    }
  })
  bindCanvasEvents()
  try {
    await modeler.importXML(DEFAULT_XML)
    fitViewport()
  } catch {
    ElMessage.error('流程图初始化失败')
  }
  await nextTick()
  panelRef.value?.refreshNodes?.()
  await refreshAll()
  await loadStartForm()
  await loadDraftFromQuery()
})

/**
 * AI 助手创建草稿后会带 draftId 跳转过来：把服务端编译好的 BPMN 载入画布。
 * 只载入不部署，用户确认后仍走「部署当前图」。
 */
async function loadDraftFromQuery() {
  const draftId = route.query.draftId
  if (!draftId) return
  try {
    const { data } = await http.get(`/api/workflow/drafts/${draftId}`)
    processKey.value = data.processKey
    activeTab.value = 'design'
    defOpen.value = false
    await nextTick()
    await waitCanvasSized(canvasRef.value)
    await modeler.importXML(data.bpmnXml)
    fitViewport()
    panelRef.value?.refreshNodes?.()
    // 把 AI 生成的节点/连线配置回填到右侧属性面板，用户可以直接看到并继续改
    const spec = parseSpec(data.spec)
    if (spec) {
      panelRef.value?.loadConfigs?.(buildPanelConfigs(spec))
    }
    ElMessage.success(`已载入草稿「${data.name || data.processKey}」，确认后点「部署当前图」`)
    // 清掉 query，避免刷新页面时重复提示
    router.replace({ path: route.path })
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

// 已经停在设计器页面时，AI 只改 query 不会触发 onMounted，必须单独监听
watch(() => route.query.draftId, (draftId) => {
  if (draftId) loadDraftFromQuery()
})

// ==================== AI 草稿 → 属性面板 ====================

function parseSpec(raw: any) {
  if (!raw) return null
  try {
    return typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch {
    return null
  }
}

/**
 * 服务端把 AI 的节点配置原样存进 spec。这里转换成 NodeConfigPanel 的同构结构，
 * 用户打开设计器就能看到找人方式、表单、按钮、超时和分支条件，而不是空面板。
 */
function buildPanelConfigs(spec: any) {
  const result: any[] = []
  for (const node of Array.isArray(spec.nodes) ? spec.nodes : []) {
    if (node.type !== 'APPROVAL') continue
    const config = node.config || {}
    const assigneeValue = config.assigneeValue || node.assignee || ''
    const assigneeType = config.assigneeType
      || (assigneeValue === '${initiator}' ? 'INITIATOR' : assigneeValue ? 'USER' : 'INITIATOR')
    result.push({
      nodeId: node.id,
      nodeName: node.name || node.id,
      nodeType: 'userTask',
      assigneeType,
      assigneeValue,
      signType: config.signType || 'SINGLE',
      // AI 可能把数值写成 null/字符串，统一转成数字再交给面板与部署接口
      signRatio: Number(config.signRatio) || 100,
      formCode: config.formCode || '',
      fieldPolicy: config.fieldPolicy || {},
      decisionConfig: config.decisionConfig || {},
      triggerConfig: config.triggerConfig || {},
      conditionConfig: {},
      timeoutMinutes: Number(config.timeoutMinutes) || 0
    })
  }
  const edges = Array.isArray(spec.edges) ? spec.edges : []
  edges.forEach((edge: any, index: number) => {
    // 能拆成单字段就用结构化面板；组合条件/自由表达式原样保留，不能丢
    const conditionConfig = parseCondition(edge.condition)
      || (edge.condition ? { expression: String(edge.condition).trim() } : null)
    if (!conditionConfig) return
    result.push({
      nodeId: edge.id || `flow_${index + 1}`,
      nodeName: edge.name || '',
      nodeType: 'sequenceFlow',
      conditionConfig
    })
  })
  return result
}

/** 把 ${amount >= 5000} 解析成面板的结构化条件；解析不了就不填，XML 里仍保留表达式。 */
function parseCondition(condition?: string) {
  if (!condition) return null
  const text = condition.trim().replace(/^\$\{/, '').replace(/\}$/, '').trim()
  const match = /^([A-Za-z0-9_.]+)\s*(>=|<=|==|!=|>|<)\s*(.+)$/.exec(text)
  if (!match) return null
  const rawValue = match[3].trim().replace(/^['"]|['"]$/g, '')
  return {
    field: match[1],
    operator: match[2],
    value: rawValue,
    valueType: /^-?\d+(\.\d+)?$/.test(rawValue) ? 'NUMBER' : 'STRING'
  }
}

onBeforeUnmount(() => {
  disposeCanvasProvider?.()
  disposeCanvasProvider = null
  if (modeler) modeler.destroy()
  if (defViewer) defViewer.destroy()
  if (instViewer) instViewer.destroy()
})
</script>

<style scoped>
:deep(.el-tabs__content) { padding: 18px 6px; }
/* 画布 ⇄ 右侧面板双向联动：面板点一项，画布对应节点/连线要用行动色亮出来，
   默认的蓝色虚线框在浅色画布上太不起眼，连线尤其看不出来 */
.wf-canvas :deep(.djs-element.selected .djs-outline) {
  visibility: visible !important;
  stroke: #B94E18;
  stroke-width: 2.5px;
  stroke-dasharray: none;
}
.short-id { cursor: help; border-bottom: 1px dashed #C9D3CE; }
.num-warn { color: #B3261E; font-weight: 600; }
.wf-strategy { display: flex; flex-direction: column; gap: 16px; align-items: flex-start; }
.wf-strategy :deep(.el-radio) { height: auto; align-items: flex-start; white-space: normal; margin-right: 0; }
.wf-strategy :deep(.el-radio__label) { white-space: normal; line-height: 1.6; }
.wf-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 14px; }
.wf-stats > div { background: #F4F8F6; border: 1px solid #DCE7E1; border-radius: 12px; padding: 14px 16px; }
.wf-stats b { display: block; font-size: 22px; color: #17564A; }
.wf-stats span { font-size: 12px; color: #6B7671; }
.wf-chart { height: 300px; border: 1px solid #DCE7E1; border-radius: 12px; background: #FFFFFF; overflow: hidden; }
.wf-alert { display: flex; flex-direction: column; gap: 4px; margin: 14px 0 10px; padding: 12px 16px; background: #FCEBEA; border: 1px solid #E9B9B5; border-radius: 10px; color: #8B2A22; font-size: 13px; }
.legend { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin: 0 4px 0 12px; vertical-align: middle; }
.legend.done { background: #2E7D4F; }
.legend.current { background: #B94E18; }
.legend.rollback { background: #B3261E; }
/* 只给「形状」上色。以前用 .djs-visual > * 把 <text> 也一起描边+浅色填充，
   节点名称会糊成一团；文字单独强制深色、无描边。 */
:deep(.wf-done .djs-visual > :is(rect, circle, ellipse, polygon)) { stroke: #2E7D4F !important; fill: #E8F4EC !important; }
:deep(.wf-current .djs-visual > :is(rect, circle, ellipse, polygon)) { stroke: #B94E18 !important; stroke-width: 3px !important; fill: #FCE9D9 !important; }
:deep(.wf-rollback .djs-visual > :is(rect, circle, ellipse, polygon)) { stroke: #B3261E !important; stroke-width: 3px !important; fill: #FBE4E2 !important; }
:deep(.wf-chart .djs-visual > text),
:deep(.wf-chart .djs-label),
:deep(.wf-chart .djs-label > text) { fill: #1C1B18 !important; stroke: none !important; font-size: 12px !important; }
</style>
