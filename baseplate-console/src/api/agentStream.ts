import { TOKEN_KEY } from './client'

/** 后端 SSE 帧的统一形态：seq 严格递增，type 为事件名。 */
export interface AgentStreamEvent {
  seq: number
  type: string
  [key: string]: any
}

export interface AgentStreamHandlers {
  onEvent: (event: AgentStreamEvent) => void
}

/**
 * POST + fetch 读取 SSE。
 *
 * 不用 EventSource：它只支持 GET，且无法带 Authorization 头。
 * 这里手动按空行切帧，忽略 `:ping` 心跳注释。
 */
export async function streamAgentChat(
  body: { agentCode?: string; sessionId: number | null; pageKey: string; message: string },
  handlers: AgentStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  return postStream('/api/agent/chat/stream', body, handlers, signal)
}

/** HITL 决策后继续同一条对话；返回的仍然是同一套 SSE 事件。 */
export async function streamHitlDecision(
  approvalId: number,
  body: { approved: boolean; comment?: string },
  handlers: AgentStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  return postStream(`/api/agent/hitl/${approvalId}/decision`, body, handlers, signal)
}

/** 前端执行完外部工具后回传结果，服务端恢复 Agent。 */
export async function streamExternalResult(
  callId: string,
  body: { ok: boolean; result?: unknown; error?: string },
  handlers: AgentStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  return postStream(`/api/agent/external-calls/${callId}/result`, body, handlers, signal)
}

async function postStream(
  url: string,
  body: unknown,
  handlers: AgentStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const token = localStorage.getItem(TOKEN_KEY) || ''
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body),
    signal
  })

  if (!response.ok || !response.body) {
    let detail = `请求失败（HTTP ${response.status}）`
    try {
      const data = await response.json()
      detail = data.detail || data.title || detail
    } catch {
      // 响应不是 JSON，保留默认文案
    }
    throw new Error(detail)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      handleFrame(frame, handlers)
      boundary = buffer.indexOf('\n\n')
    }
  }
}

function handleFrame(frame: string, handlers: AgentStreamHandlers) {
  let eventName = ''
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (!dataLines.length) return
  try {
    const payload = JSON.parse(dataLines.join('\n'))
    handlers.onEvent({ ...payload, type: payload.type || eventName })
  } catch {
    // 非 JSON 帧（例如代理注入的内容）直接忽略
  }
}
