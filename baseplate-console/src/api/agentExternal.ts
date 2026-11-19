/**
 * 外部工具的前端执行器注册表。
 *
 * <p>外部工具由服务端下发参数、前端在页面里执行。具体工具（例如操作画布、
 * 打开业务弹窗）由对应页面注册处理器；没有注册时对话会停在「等待页面处理」，
 * 不会假装执行成功。</p>
 */
export interface AgentExternalResult {
  ok: boolean
  result?: unknown
  error?: string
}

export type AgentExternalHandler = (
  args: Record<string, any>,
  context: { callId: string; tool: string }
) => Promise<AgentExternalResult>

const handlers = new Map<string, AgentExternalHandler>()

export function registerAgentExternalTool(toolCode: string, handler: AgentExternalHandler) {
  handlers.set(toolCode, handler)
}

export function getAgentExternalToolHandler(toolCode: string) {
  return handlers.get(toolCode)
}
