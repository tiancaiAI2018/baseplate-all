import { defineStore } from 'pinia'
import http from '../api/client'
import {
  streamAgentChat,
  streamExternalResult,
  streamHitlDecision,
  type AgentStreamEvent
} from '../api/agentStream'
import { getAgentExternalToolHandler } from '../api/agentExternal'
import { useAuthStore } from './auth'

export interface AgentHitlCall {
  callId: string
  tool: string
  args: Record<string, any>
}

export interface AgentChatBlock {
  type: string
  text?: string
  callId?: string
  tool?: string
  ok?: boolean
  state?: string
  message?: string
  approvalId?: number
  toolCalls?: AgentHitlCall[]
  status?: string
  action?: string
  route?: string
  query?: Record<string, any>
  label?: string
  name?: string
  args?: Record<string, any>
  /** ui 卡片的子类型：task_list / workflow_preview，没有则为普通跳转按钮 */
  kind?: string
  title?: string
  tasks?: Array<Record<string, any>>
  bpmnXml?: string
}

export interface AgentChatMessage {
  role: 'USER' | 'ASSISTANT'
  content: string
  blocks: AgentChatBlock[]
  streaming?: boolean
  /** 后端落库后的消息 id：done 事件回传，历史回放来自列表；评分需要它 */
  id?: number
  /** 用户评分：USEFUL / NOT_USEFUL，null 表示未评 */
  feedback?: 'USEFUL' | 'NOT_USEFUL' | null
}

export interface AgentSessionItem {
  id: number
  title?: string
  pageKey?: string
  messageCount: number
  lastActiveAt: string
}

/**
 * 悬浮助手状态：当前页面可用性、会话历史、流式消息块。
 * 会话 id 以后端返回为准；刷新页面后靠它恢复历史。
 */
export const useAgentStore = defineStore('agent', {
  state: () => ({
    enabled: false,
    open: false,
    agentCode: '',
    agentName: '',
    pageKey: '',
    sessions: [] as AgentSessionItem[],
    sessionId: null as number | null,
    messages: [] as AgentChatMessage[],
    streaming: false,
    error: '',
    controller: null as AbortController | null,
    /** 流式请求版本号：旧请求的 finally 不能把新请求的流式状态清掉 */
    streamToken: 0,
    /**
     * 页面执行很快时，外部工具结果会早于当前 SSE 流结束返回。
     * 此时不能直接提交（会和正在进行的流抢会话），先排队，等流结束再回传。
     */
    pendingExternal: [] as Array<{
      block: AgentChatBlock
      response: { ok: boolean; result?: unknown; error?: string }
    }>
  }),

  actions: {
    /** 页面切换时调用：当前页没有绑定 Agent 或无权限就隐藏入口。 */
    async bootstrap(pageKey: string) {
      const auth = useAuthStore()
      if (!auth.loggedIn || !auth.has('agent:chat')) {
        this.enabled = false
        this.open = false
        return
      }
      this.pageKey = pageKey
      try {
        const { data } = await http.get('/api/agent/chat/bootstrap', { params: { pageKey } })
        this.enabled = !!data.enabled
        this.agentCode = data.agentCode || ''
        this.agentName = data.agentName || ''
        if (!this.enabled) {
          this.open = false
        }
      } catch {
        this.enabled = false
        this.open = false
      }
    },

    toggle() {
      if (!this.enabled) return
      this.open = !this.open
      if (this.open && !this.sessions.length) {
        this.loadSessions().catch(() => undefined)
      }
    },

    close() {
      this.open = false
    },

    newChat() {
      this.sessionId = null
      this.messages = []
      this.error = ''
    },

    async loadSessions() {
      const { data } = await http.get<AgentSessionItem[]>('/api/agent/sessions')
      this.sessions = data
    },

    async continueSession(sessionId: number) {
      const { data } = await http.get(`/api/agent/sessions/${sessionId}/messages`)
      this.sessionId = sessionId
      this.messages = (data as any[]).map((row) => ({
        id: row.id,
        role: row.role,
        content: row.content || '',
        blocks: Array.isArray(row.blocks) ? row.blocks : [],
        feedback: row.feedback || null
      }))
      // 刷新后把仍待确认的 HITL 找回来，否则审批卡会消失、对话无法继续
      try {
        const { data: pending } = await http.get('/api/agent/hitl/pending', { params: { sessionId } })
        if (Array.isArray(pending) && pending.length) {
          this.messages.push({
            role: 'ASSISTANT',
            content: '',
            blocks: pending.map((item: any) => ({
              type: 'hitl',
              approvalId: item.approvalId,
              toolCalls: item.toolCalls || [],
              status: 'PENDING'
            }))
          })
        }
      } catch {
        // 没有待审批项或接口不可用时忽略
      }
      this.error = ''
    },

    async deleteSession(sessionId: number) {
      await http.delete(`/api/agent/sessions/${sessionId}`)
      if (this.sessionId === sessionId) {
        this.newChat()
      }
      await this.loadSessions()
    },

    async send(text: string) {
      const message = text.trim()
      if (!message || this.streaming || !this.enabled) return

      this.error = ''
      this.messages.push({
        role: 'USER',
        content: message,
        blocks: [{ type: 'text', text: message }]
      })
      const assistant: AgentChatMessage = {
        role: 'ASSISTANT',
        content: '',
        blocks: [],
        streaming: true
      }
      this.messages.push(assistant)
      this.streaming = true
      this.controller = new AbortController()
      const token = ++this.streamToken

      try {
        await streamAgentChat(
          {
            agentCode: this.agentCode || undefined,
            sessionId: this.sessionId,
            pageKey: this.pageKey,
            message
          },
          { onEvent: (event) => this.consume(event, token) },
          this.controller.signal
        )
      } catch (error: any) {
        if (error?.name !== 'AbortError') {
          this.error = error?.message || '对话失败'
          assistant.blocks.push({ type: 'error', message: this.error })
        }
      } finally {
        // 只有当前流才能解锁输入框；旧流晚到的 finally 不能影响新一轮对话
        if (this.streamToken === token) {
          assistant.streaming = false
          this.streaming = false
          this.controller = null
          // 本轮思考期间执行完的外部工具结果在这里补交，模型才能继续处理
          void this.flushPendingExternal()
        }
        this.loadSessions().catch(() => undefined)
      }
    },

    stop() {
      this.streamToken += 1
      this.controller?.abort()
      this.controller = null
      this.streaming = false
      // 用户主动停止后不再自动恢复；丢弃本轮尚未回传的外部工具结果
      this.pendingExternal = []
      const last = this.messages[this.messages.length - 1]
      if (last) last.streaming = false
    },

    /**
     * 同意/拒绝后继续同一条流。决策接口本身返回 SSE，
     * 所以恢复后的文本、下一轮工具调用都会追加在同一个对话气泡里。
     */
    async decideHitl(block: AgentChatBlock, approved: boolean) {
      if (!block.approvalId || this.streaming) return
      block.status = approved ? 'APPROVED' : 'REJECTED'
      this.error = ''
      this.streaming = true
      this.controller = new AbortController()
      const token = ++this.streamToken
      try {
        await streamHitlDecision(
          block.approvalId,
          { approved },
          { onEvent: (event) => this.consume(event, token) },
          this.controller.signal
        )
      } catch (error: any) {
        if (error?.name !== 'AbortError') {
          this.error = error?.message || '恢复对话失败'
        }
      } finally {
        if (this.streamToken === token) {
          this.streaming = false
          this.controller = null
          void this.flushPendingExternal()
        }
        this.loadSessions().catch(() => undefined)
      }
    },

    /** 页面注册了处理器时自动执行外部工具，并把结果回传给服务端。 */
    async runExternalTool(block: AgentChatBlock) {
      if (!block.callId || !block.tool) return
      const handler = getAgentExternalToolHandler(block.tool)
      if (!handler) return
      block.status = 'RUNNING'
      let response: { ok: boolean; result?: unknown; error?: string }
      try {
        response = await handler(block.args || {}, { callId: block.callId, tool: block.tool })
      } catch (error: any) {
        response = { ok: false, error: error?.message || '页面执行失败' }
      }
      await this.submitExternalResult(block, response)
    },

    async submitExternalResult(
      block: AgentChatBlock,
      response: { ok: boolean; result?: unknown; error?: string }
    ) {
      if (!block.callId) return
      // 当前流还没结束时提交，会和正在进行的 SSE 抢同一个会话，服务端也可能拒绝；
      // 先排队，等本轮流结束后由 flushPendingExternal 统一回传。
      // 不排队的话执行快的工具（例如立刻返回 400 的保存接口）结果会被直接丢掉，
      // 模型永远收不到失败原因，对话就停在「执行失败」。
      if (this.streaming) {
        this.pendingExternal.push({ block, response })
        block.status = response.ok ? 'DONE' : 'FAILED'
        return
      }
      this.streaming = true
      this.controller = new AbortController()
      const token = ++this.streamToken
      try {
        await streamExternalResult(
          block.callId,
          { ok: response.ok, result: response.result, error: response.error },
          { onEvent: (event) => this.consume(event, token) },
          this.controller.signal
        )
      } catch (error: any) {
        if (error?.name !== 'AbortError') {
          this.error = error?.message || '外部工具恢复失败'
          block.status = 'FAILED'
        }
      } finally {
        if (this.streamToken === token) {
          this.streaming = false
          this.controller = null
          // 链式外部工具：这次恢复又触发了新工具时，继续提交排队结果
          void this.flushPendingExternal()
        }
        this.loadSessions().catch(() => undefined)
      }
    },

    /** 当前流结束后，把排队的外部工具结果逐个回传；每次提交会开启新的流。 */
    async flushPendingExternal() {
      if (this.streaming || !this.pendingExternal.length) return
      const next = this.pendingExternal.shift()
      if (!next) return
      await this.submitExternalResult(next.block, next.response)
    },

    markExternalStatus(callId: string, status: string) {
      if (!callId) return
      for (let i = this.messages.length - 1; i >= 0; i -= 1) {
        const block = [...this.messages[i].blocks]
          .reverse()
          .find((item) => item.type === 'external_tool_call' && item.callId === callId)
        if (block) {
          block.status = status
          return
        }
      }
    },

    /** 事件入口：只认后端约定的类型，未知类型忽略，避免前端被自定义事件搞崩。 */
    consume(event: AgentStreamEvent, token?: number) {
      const assistant = this.messages[this.messages.length - 1]
      if (!assistant || assistant.role !== 'ASSISTANT') return

      switch (event.type) {
        case 'session':
          if (event.sessionId) this.sessionId = Number(event.sessionId)
          break
        case 'text': {
          const delta = String(event.delta || '')
          assistant.content += delta
          this.appendTextBlock(assistant, 'text', delta)
          break
        }
        case 'thinking':
          this.appendTextBlock(assistant, 'thinking', String(event.delta || ''))
          break
        case 'tool_call':
          if (event.phase === 'start') {
            assistant.blocks.push({ type: 'tool_call', callId: event.callId, tool: event.tool })
          }
          break
        case 'tool_result':
          if (event.phase === 'end') {
            assistant.blocks.push({
              type: 'tool_result',
              callId: event.callId,
              tool: event.tool,
              ok: !!event.ok,
              state: event.state
            })
          }
          break
        case 'hitl':
          assistant.blocks.push({
            type: 'hitl',
            approvalId: Number(event.approvalId),
            toolCalls: event.toolCalls || [],
            status: 'PENDING'
          })
          break
        case 'hitl_resolved': {
          const block = assistant.blocks
            .filter((item) => item.type === 'hitl' && item.approvalId === Number(event.approvalId))
            .at(-1)
          if (block) {
            block.status = event.approved ? 'APPROVED' : 'REJECTED'
          }
          break
        }
        case 'ui':
          // 工具产出的页面指令：保留完整负载，按 kind 渲染成任务卡片 / 流程图 / 跳转按钮
          assistant.blocks.push({ ...(event as any), type: 'ui' })
          break
        case 'skill':
          assistant.blocks.push({ type: 'skill', name: event.name || event.tool })
          break
        case 'external_tool_call': {
          const calls = Array.isArray(event.calls) ? event.calls : []
          for (const call of calls) {
            const block: AgentChatBlock = {
              type: 'external_tool_call',
              callId: call.callId,
              tool: call.tool,
              args: call.args || {},
              status: 'PENDING'
            }
            assistant.blocks.push(block)
            // 只有一个调用且页面注册了处理器时才自动执行；
            // 多调用并发恢复需要排队，先交给页面逐个人工处理，避免并发流互相覆盖
            const handler = calls.length === 1 ? getAgentExternalToolHandler(call.tool) : null
            if (handler) {
              void this.runExternalTool(block)
            } else {
              block.status = 'WAITING'
            }
          }
          break
        }
        case 'external_wait':
          this.markExternalStatus(event.callId, 'WAITING')
          break
        case 'external_result':
          this.markExternalStatus(event.callId, event.ok ? 'DONE' : 'FAILED')
          break
        case 'error':
          this.error = event.message || '对话失败'
          assistant.blocks.push({ type: 'error', message: this.error })
          break
        case 'done':
          // done 是服务端明确的收尾信号：先解锁输入框并主动关闭连接。
          // 某些代理不会把 chunked 的结束块透传给浏览器，靠 EOF 解锁会一直转圈。
          if (token == null || this.streamToken === token) {
            assistant.streaming = false
            this.streaming = false
            this.controller?.abort()
            this.controller = null
          }
          // 落库后的消息 id：后续评分要用
          if (event.messageId) assistant.id = Number(event.messageId)
          break
        default:
          break
      }
    },

    appendTextBlock(message: AgentChatMessage, type: string, delta: string) {
      if (!delta) return
      const last = message.blocks[message.blocks.length - 1]
      if (last && last.type === type) {
        last.text = (last.text || '') + delta
        return
      }
      message.blocks.push({ type, text: delta })
    },

    /**
     * 给助手回复打分。点已选中的评分=取消；改选会覆盖。
     * 失败时静默恢复原状态，不打断对话。
     */
    async rateMessage(message: AgentChatMessage, rating: 'USEFUL' | 'NOT_USEFUL') {
      if (!message.id) return
      const previous = message.feedback || null
      const next = previous === rating ? null : rating
      message.feedback = next
      try {
        await http.post(`/api/agent/sessions/messages/${message.id}/feedback`, { rating: next })
      } catch {
        message.feedback = previous
      }
    }
  }
})
