import http from './client'

/** 与后端 AgentConfigService.ToolView 对齐。 */
export interface AgentToolView {
  toolCode: string
  name: string
  description: string
  toolType: string
  builtin: boolean
  editable: boolean
  pages: string[]
  permissionCode?: string
  riskLevel: string
  minApproval: string
  defaultApproval: string
  enabled: boolean
  approvalMode: string
  effectiveApproval: string
}

export interface AgentDefinitionView {
  id: number
  agentCode: string
  name: string
  description?: string
  provider: string
  modelName: string
  baseUrl?: string
  apiKeyConfigured: boolean
  systemPrompt?: string
  modelParams: Record<string, any>
  allowedPages: string[]
  status: string
  revision: number
}

export interface AgentSkillBindingView {
  skillName: string
  description?: string
  version?: string
  status: string
  enabled: boolean
}

export interface SkillDetailView {
  skillName: string
  description?: string
  version?: string
  path: string
  content: string
}

export interface AgentSkillBindingPayload {
  skillName: string
  enabled: boolean
}

export interface DefinitionPayload {
  agentCode?: string
  name: string
  description?: string
  provider: string
  modelName: string
  baseUrl?: string
  apiKey?: string
  systemPrompt?: string
  modelParams: Record<string, any>
  allowedPages: string[]
  status: string
}

export const agentApi = {
  list: () => http.get<AgentDefinitionView[]>('/api/agent/definitions'),
  create: (payload: DefinitionPayload) => http.post<AgentDefinitionView>('/api/agent/definitions', payload),
  update: (id: number, payload: DefinitionPayload) =>
    http.put<AgentDefinitionView>(`/api/agent/definitions/${id}`, payload),
  remove: (id: number) => http.delete(`/api/agent/definitions/${id}`),
  tools: (id: number) => http.get<AgentToolView[]>(`/api/agent/definitions/${id}/tools`),
  saveTools: (id: number, items: Array<{ toolCode: string; enabled: boolean; approvalMode: string }>) =>
    http.put(`/api/agent/definitions/${id}/tools`, items),
  skillsOfAgent: (id: number) =>
    http.get<AgentSkillBindingView[]>(`/api/agent/definitions/${id}/skills`),
  saveAgentSkills: (id: number, items: AgentSkillBindingPayload[]) =>
    http.put(`/api/agent/definitions/${id}/skills`, items),
  test: (id: number) => http.post<{ ok: boolean; latencyMs: number; reply: string }>(`/api/agent/definitions/${id}/test`),
  sessions: () => http.get('/api/agent/sessions'),
  messages: (sessionId: number) => http.get(`/api/agent/sessions/${sessionId}/messages`),
  deleteSession: (sessionId: number) => http.delete(`/api/agent/sessions/${sessionId}`),
  pendingHitl: (sessionId: number) =>
    http.get('/api/agent/hitl/pending', { params: { sessionId } }),
  skills: () => http.get('/api/agent/skills'),
  rescanSkills: () => http.post<{ scanned: number }>('/api/agent/skills/rescan'),
  skillDetail: (skillName: string) =>
    http.get<SkillDetailView>(`/api/agent/skills/${encodeURIComponent(skillName)}`),
  createSkill: (payload: { skillName: string; description?: string; modes?: string; content?: string }) =>
    http.post<SkillDetailView>('/api/agent/skills', payload),
  updateSkill: (skillName: string, content: string) =>
    http.put<SkillDetailView>(`/api/agent/skills/${encodeURIComponent(skillName)}`, { content }),
  deleteSkill: (skillName: string) =>
    http.delete(`/api/agent/skills/${encodeURIComponent(skillName)}`),
  uploadSkillZip: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<{ imported: number }>('/api/agent/skills/upload', form)
  },
  uploadSkillFolder: (files: File[]) => {
    const form = new FormData()
    // 浏览器只给相对路径；第一段是用户选中的目录名，正好作为技能目录名
    const root = files[0]?.webkitRelativePath?.split('/')[0] || 'imported-skill'
    for (const file of files) {
      const relative = file.webkitRelativePath || file.name
      const rest = relative.split('/').slice(1).join('/') || file.name
      form.append('files', file)
      form.append('paths', `${root}/${rest}`)
    }
    return http.post<{ imported: number }>('/api/agent/skills/upload-folder', form)
  },
  toolDirectory: (query: ToolDirectoryQuery = {}) =>
    // 旧版返回数组，新版返回带分组/分页的对象；两层兼容由调用方处理
    http.get<ToolDirectoryResult>('/api/agent/tools/directory', { params: query }),
  createTool: (payload: ToolDefinitionPayload) =>
    http.post<ToolDefinitionView>('/api/agent/tools/directory', payload),
  updateTool: (toolCode: string, payload: ToolDefinitionPayload) =>
    http.put<ToolDefinitionView>(`/api/agent/tools/directory/${toolCode}`, payload),
  deleteTool: (toolCode: string) => http.delete(`/api/agent/tools/directory/${toolCode}`)
}

export interface ToolDefinitionView {
  toolCode: string
  name: string
  description?: string
  toolType: string
  builtin: boolean
  editable: boolean
  pages: string[]
  permissionCode?: string
  riskLevel: string
  minApproval: string
  defaultApproval: string
  paramsSchema: Record<string, any>
  config: Record<string, any>
  groupName?: string
  source?: string
  endpointServiceCode?: string
  endpointCode?: string
}

export interface ToolDefinitionPayload {
  toolCode: string
  name: string
  description?: string
  toolType: string
  pages: string[]
  permissionCode?: string
  riskLevel: string
  minApproval: string
  defaultApproval: string
  paramsSchema: Record<string, any>
  config: Record<string, any>
  groupName?: string
}

export interface ToolDirectoryQuery {
  group?: string
  keyword?: string
  page?: number
  size?: number
}

/** 新版目录响应（带分组与分页）；旧版接口直接返回数组。 */
export interface ToolDirectoryPage {
  items: ToolDefinitionView[]
  total: number
  groups: string[]
}

export type ToolDirectoryResult = ToolDefinitionView[] | ToolDirectoryPage
