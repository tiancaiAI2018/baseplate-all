<template>
  <div v-if="agent.enabled" class="agent-assistant">
    <transition name="agent-pop">
      <section v-if="agent.open" class="agent-panel">
        <header class="agent-head">
          <div class="title">
            <div class="dot"></div>
            <div>
              <div class="name">{{ agent.agentName || 'AI 助手' }}</div>
              <div class="sub">{{ route.meta.title || '当前页面' }}</div>
            </div>
          </div>
          <div class="actions">
            <el-button text circle title="历史会话" @click="showHistory = !showHistory">
              <el-icon><Clock /></el-icon>
            </el-button>
            <el-button text circle title="新对话" @click="startNew">
              <el-icon><Plus /></el-icon>
            </el-button>
            <el-button text circle title="收起" @click="agent.close()">
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
        </header>

        <div v-if="showHistory" class="history">
          <div class="history-head">最近会话</div>
          <div v-if="!agent.sessions.length" class="history-empty">还没有历史会话</div>
          <div v-for="item in agent.sessions" :key="item.id" class="history-item">
            <div class="history-main" @click="pickSession(item.id)">
              <div class="history-title">{{ item.title || '未命名会话' }}</div>
              <div class="history-meta">
                {{ item.pageKey || '—' }} · {{ (item.lastActiveAt || '').slice(0, 16).replace('T', ' ') }}
              </div>
            </div>
            <el-button text size="small" @click.stop="agent.deleteSession(item.id)">删除</el-button>
          </div>
        </div>

        <div ref="listRef" class="agent-body">
          <div v-if="!agent.messages.length" class="welcome">
            <p class="hello">你好，我是 {{ agent.agentName || 'AI 助手' }}。</p>
            <p class="hint">
              当前页面：<code>{{ agent.pageKey }}</code>。
              可以直接描述你要做的事，例如「创建一个请假流程」。
            </p>
          </div>

          <div v-for="(message, index) in agent.messages" :key="index" class="message"
               :class="message.role === 'USER' ? 'from-user' : 'from-agent'">
            <div class="bubble">
              <template v-if="message.role === 'USER'">
                <div class="plain">{{ message.content }}</div>
              </template>
              <template v-else>
                <div v-for="(block, blockIndex) in message.blocks" :key="blockIndex" class="block">
                  <el-collapse v-if="block.type === 'thinking'" class="thinking">
                    <el-collapse-item title="思考过程" :name="String(blockIndex)">
                      <div class="thinking-text">{{ block.text }}</div>
                    </el-collapse-item>
                  </el-collapse>
                  <AgentMarkdown v-else-if="block.type === 'text'" :text="block.text || ''" />
                  <div v-else-if="block.type === 'tool_call'" class="tool-chip">
                    <el-icon><Tools /></el-icon>
                    <span>调用工具：{{ block.tool }}</span>
                  </div>
                  <div v-else-if="block.type === 'tool_result'" class="tool-chip"
                       :class="block.ok ? 'is-ok' : 'is-fail'">
                    <el-icon>
                      <CircleCheck v-if="block.ok" />
                      <CircleClose v-else />
                    </el-icon>
                    <span>工具结果：{{ block.tool }}</span>
                  </div>
                  <div v-else-if="block.type === 'hitl'" class="hitl-card">
                    <div class="hitl-title">需要你确认后才能继续</div>
                    <div v-for="call in block.toolCalls || []" :key="call.callId" class="hitl-call">
                      <div class="hitl-tool">{{ call.tool }}</div>
                      <code class="hitl-args">{{ formatArgs(call.args) }}</code>
                    </div>
                    <div v-if="block.status === 'PENDING'" class="hitl-actions">
                      <el-button size="small" :disabled="agent.streaming"
                                 @click="agent.decideHitl(block, false)">拒绝</el-button>
                      <el-button size="small" type="primary" :disabled="agent.streaming"
                                 @click="agent.decideHitl(block, true)">同意执行</el-button>
                    </div>
                    <el-tag v-else size="small" effect="plain"
                            :type="block.status === 'APPROVED' ? 'success' : 'info'">
                      {{ block.status === 'APPROVED' ? '已同意' : '已拒绝' }}
                    </el-tag>
                  </div>
                  <el-tag v-else-if="block.type === 'skill'" size="small" type="success" effect="plain">
                    使用技能：{{ block.name || block.tool }}
                  </el-tag>
                  <template v-else-if="block.type === 'ui' && block.kind === 'task_list'">
                    <div class="task-card">
                      <div class="task-card-head">{{ block.title || '我的待办' }}</div>
                      <div v-if="!block.tasks?.length" class="task-empty">当前没有待办任务</div>
                      <div v-for="task in block.tasks || []" :key="task.taskId" class="task-row">
                        <div class="task-main">
                          <div class="task-name">{{ task.name || '未命名任务' }}</div>
                          <div class="task-meta">
                            {{ task.processName || task.processKey || '流程' }}
                            <template v-if="task.businessKey"> · {{ task.businessKey }}</template>
                            <template v-if="task.startUserName"> · 发起人 {{ task.startUserName }}</template>
                          </div>
                        </div>
                        <el-button size="small" type="primary" plain @click="openTask(task)">办理</el-button>
                      </div>
                    </div>
                  </template>
                  <AgentBpmnCard v-else-if="block.type === 'ui' && block.kind === 'workflow_preview'"
                                 :xml="block.bpmnXml || ''" :title="block.title"
                                 :open-route="block.route" />
                  <template v-else-if="block.type === 'ui' && block.kind === 'instance_list'">
                    <div class="task-card">
                      <div class="task-card-head">{{ block.title || '流程实例' }}</div>
                      <div v-if="!block.items?.length" class="task-empty">没有符合条件的流程</div>
                      <div v-for="row in block.items || []" :key="row.instanceId" class="task-row">
                        <div class="task-main">
                          <div class="task-name">{{ row.title || row.businessKey || row.processName || '未命名流程' }}</div>
                          <div class="task-meta">{{ row.subtitle || row.processName }}</div>
                        </div>
                        <el-button v-if="row.instanceId" size="small" type="primary" plain
                                   @click="openInstance(row.instanceId)">看进度</el-button>
                      </div>
                    </div>
                  </template>
                  <AgentInstanceProgress v-else-if="block.type === 'ui' && block.kind === 'instance_progress'"
                                         :instance-id="block.instanceId" :title="block.title"
                                         :status="block.status" :current-nodes="block.currentNodes"
                                         @open="openInstance" />
                  <template v-else-if="block.type === 'ui' && block.kind === 'stat_group'">
                    <div class="stat-card">
                      <div class="task-card-head">{{ block.title || '概览' }}</div>
                      <div class="stat-grid">
                        <div v-for="cell in block.stats || []" :key="cell.label" class="stat-cell">
                          <div class="stat-value">
                            {{ cell.value }}<span class="stat-unit">{{ cell.unit }}</span>
                          </div>
                          <div class="stat-label">{{ cell.label }}</div>
                          <div class="stat-hint">{{ cell.hint }}</div>
                        </div>
                      </div>
                    </div>
                  </template>
                  <div v-else-if="block.type === 'ui'" class="ui-card">
                    <div class="ui-text">{{ block.label || 'AI 已准备好下一步' }}</div>
                    <el-button size="small" type="primary" @click="openUi(block)">打开</el-button>
                  </div>
                  <div v-else-if="block.type === 'external_tool_call'" class="hitl-card external-card">
                    <div class="hitl-title">页面操作：{{ block.tool }}</div>
                    <code class="hitl-args">{{ formatArgs(block.args) }}</code>
                    <el-tag size="small" effect="plain"
                            :type="block.status === 'DONE' ? 'success' : (block.status === 'FAILED' ? 'danger' : 'info')">
                      {{ externalStatusText(block.status) }}
                    </el-tag>
                  </div>
                  <el-alert v-else-if="block.type === 'error'" :title="block.message"
                            type="error" :closable="false" />
                </div>
                <div v-if="message.streaming && !message.content" class="typing">正在思考…</div>
                <!-- 评分：只在回复结束且已落库（有 id）时出现；点同一项=取消 -->
                <div v-if="message.role === 'ASSISTANT' && !message.streaming && message.id"
                     class="rate-row">
                  <span class="rate-label">这条回复有帮助吗？</span>
                  <button class="rate-btn" :class="{ active: message.feedback === 'USEFUL' }"
                          title="有用"
                          @click="agent.rateMessage(message, 'USEFUL')">👍</button>
                  <button class="rate-btn" :class="{ active: message.feedback === 'NOT_USEFUL' }"
                          title="没用"
                          @click="agent.rateMessage(message, 'NOT_USEFUL')">👎</button>
                </div>
              </template>
            </div>
          </div>
        </div>

        <div v-if="agent.error" class="error-bar">{{ agent.error }}</div>

        <footer class="agent-foot">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="输入问题，Enter 发送，Shift+Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <div class="foot-actions">
            <el-button v-if="agent.streaming" size="small" @click="agent.stop()">停止</el-button>
            <el-button type="primary" size="small"
                       :disabled="!draft.trim() || agent.streaming" @click="send">发送</el-button>
          </div>
        </footer>

        <!-- 进度卡片的「查看详情」在助手面板内直接开抽屉，不打断当前页面 -->
        <InstanceDetailDrawer v-model="detailOpen" :instance-id="activeInstanceId" />
      </section>
    </transition>

    <button v-if="!agent.open" class="agent-bubble" @click="agent.toggle()">
      <el-icon :size="20"><ChatDotRound /></el-icon>
      <span>AI 助手</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, CircleCheck, CircleClose, Clock, Close, Plus, Tools } from '@element-plus/icons-vue'
import { useAgentStore } from '../../stores/agent'
import { registerAgentExternalTool } from '../../api/agentExternal'
import { readAgentCanvas } from '../../api/agentCanvas'
import http from '../../api/client'
import AgentMarkdown from './AgentMarkdown.vue'
import AgentBpmnCard from './AgentBpmnCard.vue'
import AgentInstanceProgress from './AgentInstanceProgress.vue'
import InstanceDetailDrawer from '../InstanceDetailDrawer.vue'
import { buildFormArtifacts, FIELD_NAME_PATTERN, normalizeAiFields } from './formRuleBuilder'
import { normalizeAiPage, schemaOf } from '../cend/cendDsl'

const route = useRoute()
const router = useRouter()

/** Axios 的 message 只有 “Request failed with status code 400”，
 * 真正有用的校验原因在响应体的 detail/title 里，必须透给模型，否则它只会说“失败了”。 */
function pickErrorDetail(error: any, fallback: string) {
  return error?.response?.data?.detail
    || error?.response?.data?.title
    || error?.response?.data?.message
    || error?.message
    || fallback
}

/**
 * 模型按 schema 写的是 default，后端 DTO 字段是 isDefault（default 是 Java 关键字）。
 * 不转换的话「默认分支」会静默丢失，网关就没有兜底出线。
 */
function normalizeDraftEdge(edge: any) {
  return {
    id: edge?.id,
    from: edge?.from,
    to: edge?.to,
    name: edge?.name,
    condition: edge?.condition,
    isDefault: edge?.isDefault ?? edge?.default ?? false
  }
}

/**
 * 外部工具：工作流创建由前端执行。
 * 服务端只把节点参数下发过来，这里落草稿并打开设计器，用户能看到图、手工修改后再部署。
 */
registerAgentExternalTool('workflow_create_draft', async (args) => {
  try {
    const nodes = typeof args.nodes === 'string' ? JSON.parse(args.nodes) : args.nodes
    if (!Array.isArray(nodes) || !nodes.length) {
      return { ok: false, error: '节点参数为空，无法生成流程图' }
    }
    const { data } = await http.post('/api/workflow/drafts', {
      processKey: args.processKey,
      name: args.name,
      description: args.description,
      nodes,
      edges: Array.isArray(args.edges) ? args.edges.map(normalizeDraftEdge) : []
    })
    router.push({ path: '/workflow/designer', query: { draftId: String(data.id) } })
    return {
      ok: true,
      result: {
        draftId: data.id,
        processKey: data.processKey,
        message: '已在流程设计器打开草稿，等待用户确认或修改'
      }
    }
  } catch (error: any) {
    return { ok: false, error: pickErrorDetail(error, '打开流程设计器失败') }
  }
})

/**
 * 外部工具：表单设计同样由前端执行。
 * 服务端只产出字段清单，这里编译成 form-create 规则、保存草稿，再打开表单设计器。
 * 保存走的是设计器同一个接口（只存草稿），发布仍然要用户点「保存并发布」。
 */
registerAgentExternalTool('form_create_draft', async (args) => {
  const formCode = String(args.formCode || '').trim()
  if (!FIELD_NAME_PATTERN.test(formCode)) {
    return { ok: false, error: '表单编码必须以小写字母开头，只能包含小写字母、数字和下划线' }
  }
  const fields = normalizeAiFields(args.fields)
  if (!fields.length) {
    return { ok: false, error: '字段列表为空，无法生成表单' }
  }
  const invalid = fields.find((field) => !FIELD_NAME_PATTERN.test(String(field.field || '')))
  if (invalid) {
    return { ok: false, error: `字段名不合法：${invalid.field || '(空)'}，必须是小写字母开头的英文标识` }
  }

  const { rules, schemaFields } = buildFormArtifacts(fields)
  try {
    const { data } = await http.post(`/api/metadata/FORM/${formCode}`, {
      name: args.name || formCode,
      schema: JSON.stringify({ fields: schemaFields }),
      uiSchema: JSON.stringify({ rule: rules, option: {}, actions: [] })
    })
    // v 是时间戳：同一表单再次由 AI 修改时，路由参数不变，只能靠它触发设计器重新载入
    router.push({
      path: '/workflow/forms/design',
      query: { code: formCode, v: String(Date.now()) }
    })
    return {
      ok: true,
      result: {
        code: formCode,
        version: data.version,
        fieldCount: rules.length,
        message: '已在表单设计器打开草稿，等待用户确认或修改'
      }
    }
  } catch (error: any) {
    return { ok: false, error: pickErrorDetail(error, '打开表单设计器失败') }
  }
})
/**
 * 外部工具：AI 生成 C 端页面草稿并打开设计器。
 * 模型只产出页面 DSL 参数，前端做白名单清洗后保存草稿；发布仍由用户确认。
 */
registerAgentExternalTool('cend_page_create_draft', async (args) => {
  const pageCode = String(args.pageCode || '').trim()
  if (!FIELD_NAME_PATTERN.test(pageCode)) {
    return { ok: false, error: '页面编码必须以小写字母开头，只能包含小写字母、数字和下划线' }
  }
  const dsl = normalizeAiPage(args)
  if (!dsl.blocks.length) {
    return { ok: false, error: '区块列表为空，无法生成 C 端页面' }
  }
  try {
    const { data } = await http.post(`/api/metadata/CEND_PAGE/${pageCode}`, {
      name: args.name || pageCode,
      schema: schemaOf(dsl),
      uiSchema: dsl
    })
    router.push({
      path: '/cend/pages/design',
      query: { code: pageCode, v: String(Date.now()) }
    })
    return {
      ok: true,
      result: {
        pageCode,
        version: data.version,
        blockCount: dsl.blocks.length,
        message: '已在 C 端页面设计器打开草稿，等待用户确认或修改'
      }
    }
  } catch (error: any) {
    return { ok: false, error: pickErrorDetail(error, '打开 C 端页面设计器失败') }
  }
})

/** 外部工具：读取当前 C 端页面设计器内容（含未保存修改）。 */
registerAgentExternalTool('cend_page_get_canvas', async () => {
  const snapshot = await readAgentCanvas('cend/pages/design')
  if (!snapshot.available || !snapshot.data) {
    return { ok: false, error: snapshot.error || '当前 C 端页面设计器不可用' }
  }
  return {
    ok: true,
    result: {
      ...snapshot.data,
      message: '已读取当前 C 端页面画布（含未保存修改）'
    }
  }
})

/**
 * 外部工具：读取当前流程设计器画布（含未保存修改）。
 * 画布实例由页面注册到 agentCanvas，助手不直接依赖 bpmn-js 实例。
 * 返回的 ui 由服务端转成 ui 事件，渲染成流程图缩略卡片。
 */
registerAgentExternalTool('workflow_get_canvas', async () => {
  const snapshot = await readAgentCanvas('workflow/designer')
  if (!snapshot.available || !snapshot.data) {
    return { ok: false, error: snapshot.error || '当前画布不可用' }
  }
  const { processKey, name, bpmnXml, spec } = snapshot.data
  return {
    ok: true,
    result: {
      processKey,
      name,
      nodeCount: Array.isArray(spec?.nodes) ? spec.nodes.length : 0,
      edgeCount: Array.isArray(spec?.edges) ? spec.edges.length : 0,
      spec,
      message: '已读取当前画布（含未保存修改）',
      ui: {
        kind: 'workflow_preview',
        title: `当前画布：${name || processKey || '未命名流程'}`,
        bpmnXml,
        route: '/workflow/designer'
      }
    }
  }
})

/** 外部工具：读取当前表单设计器内容（含未保存修改）。 */
registerAgentExternalTool('form_get_canvas', async () => {
  const snapshot = await readAgentCanvas('workflow/forms/design')
  if (!snapshot.available || !snapshot.data) {
    return { ok: false, error: snapshot.error || '当前表单设计器不可用' }
  }
  const { code, name, fields } = snapshot.data
  return {
    ok: true,
    result: {
      code,
      name,
      fieldCount: Array.isArray(fields) ? fields.length : 0,
      fields,
      message: '已读取当前表单设计器内容（含未保存修改）'
    }
  }
})

/**
 * 办理类操作不注册成外部工具。
 *
 * <p>同意/驳回/打回在这里的实现只是把 HTTP 接口包一层，模型多绕一次没有收益，
 * 反而让「谁批的」变模糊。办理统一由用户点列表卡片或待办页的「办理」按钮完成。</p>
 */

const agent = useAgentStore()
const draft = ref('')
const showHistory = ref(false)
const listRef = ref<HTMLElement | null>(null)

// 实例详情抽屉：进度卡片的「查看详情」和实例列表的「看进度」共用同一个入口，
// 抽屉里就是 InstanceProgress（进度图 + 轨迹 + 表单 + 操作流水），不新增路由
const detailOpen = ref(false)
const activeInstanceId = ref('')

function openInstance(instanceId: string) {
  if (!instanceId) return
  activeInstanceId.value = instanceId
  detailOpen.value = true
}

function currentPageKey() {
  return route.path.replace(/^\//, '') || 'dashboard'
}

// 页面切换 = 作用域切换：重新问后端当前页有没有 Agent，并收起历史面板
watch(
  () => route.path,
  async () => {
    showHistory.value = false
    await agent.bootstrap(currentPageKey())
  },
  { immediate: true }
)

watch(
  () => [agent.messages.length, agent.messages.at(-1)?.blocks.length, agent.messages.at(-1)?.content],
  () => scrollToBottom(),
  { deep: false }
)

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

async function send() {
  const text = draft.value
  if (!text.trim() || agent.streaming) return
  draft.value = ''
  await agent.send(text)
}

function startNew() {
  showHistory.value = false
  agent.newChat()
}

async function pickSession(sessionId: number) {
  showHistory.value = false
  await agent.continueSession(sessionId)
  scrollToBottom()
}

/** 工具参数只做展示，超长截断，避免把一整段 BPMN/JSON 撑爆气泡。 */
function formatArgs(args: Record<string, any> | undefined) {
  if (!args || !Object.keys(args).length) return '无参数'
  try {
    const text = JSON.stringify(args)
    return text.length > 300 ? `${text.slice(0, 300)}…` : text
  } catch {
    return '参数无法展示'
  }
}

function openUi(block: { route?: string; query?: Record<string, any> }) {
  if (!block.route) return
  router.push({ path: block.route, query: block.query || {} })
}

/** 任务卡片点「办理」：跳到待办页并自动打开办理抽屉（MyTodoView 读取 taskId）。 */
function openTask(task: Record<string, any>) {
  if (!task?.taskId) return
  router.push({ path: '/workflow/todo', query: { taskId: String(task.taskId) } })
}

function externalStatusText(status?: string) {
  return ({
    PENDING: '等待页面处理',
    WAITING: '等待页面处理',
    RUNNING: '页面执行中',
    DONE: '已完成',
    FAILED: '执行失败'
  } as Record<string, string>)[status || ''] || '等待页面处理'
}
</script>

<style scoped>
.agent-assistant {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2000;
}

.agent-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 18px;
  border: none;
  border-radius: 999px;
  background: var(--bp-sidebar);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 10px 24px rgba(20, 58, 49, 0.28);
}
.agent-bubble:hover {
  background: #1B4A3E;
}

.agent-panel {
  width: 400px;
  height: min(640px, 76vh);
  display: flex;
  flex-direction: column;
  background: var(--bp-card);
  border: 1px solid var(--bp-line);
  border-radius: 14px;
  box-shadow: var(--bp-shadow);
  overflow: hidden;
}

.agent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--bp-line);
  background: linear-gradient(180deg, #FBFCFB, #FFFFFF);
}
.title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--bp-accent);
}
.name {
  font-size: 14px;
  font-weight: 600;
  color: var(--bp-ink);
}
.sub {
  font-size: 11px;
  color: var(--bp-muted);
}
.actions :deep(.el-button) {
  margin-left: 2px;
}

.history {
  max-height: 200px;
  overflow-y: auto;
  border-bottom: 1px solid var(--bp-line);
  background: #FAFBFA;
  padding: 8px 12px;
}
.history-head {
  font-size: 11px;
  color: var(--bp-muted);
  margin-bottom: 6px;
}
.history-empty {
  font-size: 12px;
  color: var(--bp-muted);
  padding: 6px 0;
}
.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--bp-line);
}
.history-item:last-child {
  border-bottom: none;
}
.history-main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.history-title {
  font-size: 12px;
  color: var(--bp-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-meta {
  font-size: 11px;
  color: var(--bp-muted);
}

.agent-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: var(--bp-bg);
}
.welcome {
  background: var(--bp-card);
  border: 1px solid var(--bp-line);
  border-radius: 10px;
  padding: 14px;
  font-size: 13px;
  color: var(--bp-ink);
}
.welcome .hello {
  margin: 0 0 8px;
  font-weight: 600;
}
.welcome .hint {
  margin: 0;
  color: var(--bp-muted);
  line-height: 1.6;
}
.welcome code {
  background: #EEF2EF;
  padding: 1px 5px;
  border-radius: 4px;
}

.message {
  display: flex;
  margin-bottom: 12px;
}
.message.from-user {
  justify-content: flex-end;
}
.bubble {
  max-width: 86%;
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 13px;
  line-height: 1.6;
  background: var(--bp-card);
  border: 1px solid var(--bp-line);
  color: var(--bp-ink);
}
.from-user .bubble {
  background: #EAF3EF;
  border-color: #D6E7DF;
}
.plain {
  white-space: pre-wrap;
  word-break: break-word;
}
.block + .block {
  margin-top: 6px;
}
.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 999px;
  background: #F2F5F3;
  border: 1px solid var(--bp-line);
  color: var(--bp-muted);
}
.tool-chip.is-ok {
  color: #1F7A4D;
  border-color: #CFE8DB;
  background: #F0F8F4;
}
.tool-chip.is-fail {
  color: #B23B3B;
  border-color: #F0D2D2;
  background: #FBF1F1;
}
.thinking {
  border: none;
  --el-collapse-header-height: 28px;
}
.thinking :deep(.el-collapse-item__header) {
  font-size: 11px;
  color: var(--bp-muted);
  border: none;
  background: transparent;
}
.thinking :deep(.el-collapse-item__wrap) {
  border: none;
  background: transparent;
}
.thinking-text {
  font-size: 12px;
  color: var(--bp-muted);
  white-space: pre-wrap;
  line-height: 1.6;
}
.hitl-card {
  border: 1px solid #F0D9C8;
  background: #FFF9F4;
  border-radius: 8px;
  padding: 8px 10px;
}
.hitl-title {
  font-size: 12px;
  font-weight: 600;
  color: #A14E22;
  margin-bottom: 6px;
}
.hitl-call + .hitl-call {
  margin-top: 6px;
}
.hitl-tool {
  font-size: 12px;
  color: var(--bp-ink);
  font-weight: 600;
}
.hitl-args {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--bp-muted);
  background: #FFF;
  border: 1px solid #F3E4D8;
  border-radius: 5px;
  padding: 4px 6px;
  white-space: pre-wrap;
  word-break: break-all;
}
.hitl-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 8px;
}
.ui-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 1px solid var(--bp-line);
  background: #F7FAF8;
  border-radius: 8px;
  padding: 8px 10px;
}
.ui-text {
  font-size: 12px;
  color: var(--bp-ink);
}
.task-card {
  border: 1px solid var(--bp-line);
  background: #F7FAF8;
  border-radius: 8px;
  padding: 8px 10px;
}
.task-card-head {
  font-size: 12px;
  font-weight: 600;
  color: var(--bp-ink);
  margin-bottom: 6px;
}
.task-empty {
  font-size: 12px;
  color: var(--bp-muted);
}
.task-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
  border-top: 1px dashed var(--bp-line);
}
.task-row:first-of-type {
  border-top: none;
}
.task-main {
  min-width: 0;
}
.task-name {
  font-size: 12px;
  color: var(--bp-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-meta {
  font-size: 11px;
  color: var(--bp-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 概览数字：四个格子，窄面板下自动折成两行 */
.stat-card {
  border: 1px solid var(--bp-line);
  background: #F7FAF8;
  border-radius: 8px;
  padding: 8px 10px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}
.stat-cell {
  background: #FFFFFF;
  border: 1px solid var(--bp-line);
  border-radius: 6px;
  padding: 7px 8px;
}
.stat-value {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--bp-ink);
}
.stat-unit {
  font-size: 11px;
  font-weight: 400;
  margin-left: 2px;
  color: var(--bp-muted);
}
.stat-label {
  margin-top: 2px;
  font-size: 12px;
  color: var(--bp-ink);
}
.stat-hint {
  font-size: 10.5px;
  color: var(--bp-muted);
}
.external-card {
  border-color: #C9DCEA;
  background: #F4F9FD;
}
.external-card .hitl-title {
  color: #2F5F86;
}
.external-card .hitl-args {
  border-color: #DCE9F3;
}
.typing {
  font-size: 12px;
  color: var(--bp-muted);
  animation: blink 1.2s infinite;
}
@keyframes blink {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

/* 回复评分：默认弱化展示，悬停/选中才亮，避免干扰对话流 */
.rate-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
}
.rate-label {
  font-size: 11px;
  color: var(--bp-muted);
}
.rate-btn {
  border: 1px solid var(--bp-line);
  background: transparent;
  border-radius: 6px;
  padding: 1px 7px;
  font-size: 13px;
  cursor: pointer;
  opacity: 0.55;
  line-height: 1.5;
}
.rate-btn:hover {
  opacity: 1;
}
.rate-btn.active {
  opacity: 1;
  border-color: #6C9FE8;
  background: #EDF3FC;
}

.error-bar {
  padding: 6px 14px;
  font-size: 12px;
  color: #B23B3B;
  background: #FDF3F3;
  border-top: 1px solid #F0D2D2;
}

.agent-foot {
  border-top: 1px solid var(--bp-line);
  padding: 10px 12px 12px;
  background: var(--bp-card);
}
.foot-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.agent-pop-enter-active,
.agent-pop-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
  transform-origin: bottom right;
}
.agent-pop-enter-from,
.agent-pop-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.98);
}
</style>
