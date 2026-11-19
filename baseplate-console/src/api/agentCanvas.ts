/**
 * 页面画布状态注册表。
 *
 * <p>AI 的外部工具「读取当前画布」需要拿到页面里尚未保存的内容，
 * 但执行器注册在全局助手组件里，拿不到设计器实例。由页面在挂载时
 * 注册一个读取函数，助手按页面 key 调用；卸载时注销，避免读到已销毁的实例。</p>
 */
export interface AgentCanvasSnapshot {
  available: boolean
  error?: string
  data?: Record<string, any>
}

export type AgentCanvasProvider = () => AgentCanvasSnapshot | Promise<AgentCanvasSnapshot>

const providers = new Map<string, AgentCanvasProvider>()

/** 返回注销函数：只允许注册者自己注销，防止旧页面卸载时误删新页面的读取器。 */
export function registerAgentCanvas(key: string, provider: AgentCanvasProvider): () => void {
  providers.set(key, provider)
  return () => {
    if (providers.get(key) === provider) {
      providers.delete(key)
    }
  }
}

export async function readAgentCanvas(key: string): Promise<AgentCanvasSnapshot> {
  const provider = providers.get(key)
  if (!provider) {
    return { available: false, error: '当前页面没有可读取的画布' }
  }
  try {
    return await provider()
  } catch (error: any) {
    return { available: false, error: error?.message || '读取画布失败' }
  }
}
