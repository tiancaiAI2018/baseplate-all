import http from './client'

export interface PoolOrg {
  orgId: number
  includeChildren: boolean
}

export interface PoolBody {
  code?: string
  name: string
  description?: string
  status?: string
  claimEnabled?: boolean
  orgs: PoolOrg[]
}

export interface DispatchPolicyBody {
  processKey: string
  definitionVersion?: number
  nodeId: string
  poolId: number
  mode: string
  algorithm: string
  config: Record<string, any>
}

/** 分单域唯一出口：签到、池、策略、抢单、指派、拒绝、评价、看板。 */
export const dispatchApi = {
  today: () => http.get('/api/workflow/dispatch/checkin/today').then((r) => r.data),
  checkin: () => http.post('/api/workflow/dispatch/checkin').then((r) => r.data),
  checkout: () => http.post('/api/workflow/dispatch/checkin/checkout').then((r) => r.data),
  heartbeat: (status = 'ONLINE') =>
    http.post('/api/workflow/dispatch/checkin/heartbeat', null, { params: { status } }).then((r) => r.data),

  pools: () => http.get('/api/workflow/dispatch/pools').then((r) => r.data),
  savePool: (body: PoolBody) => http.post('/api/workflow/dispatch/pools', body).then((r) => r.data),
  updatePool: (id: number, body: PoolBody) =>
    http.put(`/api/workflow/dispatch/pools/${id}`, body).then((r) => r.data),
  suspendPool: (id: number) => http.delete(`/api/workflow/dispatch/pools/${id}`).then((r) => r.data),
  orgs: () => http.get('/api/workflow/dispatch/orgs').then((r) => r.data),
  options: () => http.get('/api/workflow/dispatch/options').then((r) => r.data),
  assignableUsers: (poolId: number) =>
    http.get('/api/workflow/dispatch/assignable-users', { params: { poolId } }).then((r) => r.data),

  policies: (processKey: string) =>
    http.get('/api/workflow/dispatch/policies', { params: { processKey } }).then((r) => r.data),
  savePolicy: (body: DispatchPolicyBody) =>
    http.put('/api/workflow/dispatch/policies', body).then((r) => r.data),

  poolTasks: () => http.get('/api/workflow/dispatch/pool-tasks').then((r) => r.data),
  myTasks: () => http.get('/api/workflow/dispatch/my-tasks').then((r) => r.data),
  claim: (id: number) => http.post(`/api/workflow/dispatch/pool-tasks/${id}/claim`).then((r) => r.data),
  assign: (taskId: string, body: { userId: string; crossOrg?: boolean; reason?: string }) =>
    http.post(`/api/workflow/dispatch/tasks/${taskId}/assign`, body).then((r) => r.data),
  backToPool: (taskId: string, reason?: string) =>
    http.post(`/api/workflow/dispatch/tasks/${taskId}/back-to-pool`, { reason }).then((r) => r.data),
  refuse: (taskId: string, body: { type: string; reasonCode: string; reasonText?: string }) =>
    http.post(`/api/workflow/dispatch/tasks/${taskId}/refuse`, body).then((r) => r.data),

  refusals: (params: { userId?: string; poolId?: number; limit?: number } = {}) =>
    http.get('/api/workflow/dispatch/refusals', { params }).then((r) => r.data),
  penalties: (params: { userId?: string; limit?: number } = {}) =>
    http.get('/api/workflow/dispatch/penalties', { params }).then((r) => r.data),
  revokePenalty: (id: number) =>
    http.post(`/api/workflow/dispatch/penalties/${id}/revoke`).then((r) => r.data),

  ratableTasks: () => http.get('/api/workflow/dispatch/ratable-tasks').then((r) => r.data),
  rate: (taskId: string, body: { score: number; tags?: string[]; comment?: string }) =>
    http.post(`/api/workflow/dispatch/tasks/${taskId}/rate`, body).then((r) => r.data),
  ratings: (params: { userId?: string; limit?: number } = {}) =>
    http.get('/api/workflow/dispatch/ratings', { params }).then((r) => r.data),

  stats: (poolId?: number) =>
    http.get('/api/workflow/dispatch/stats', { params: { poolId } }).then((r) => r.data),
  logs: (params: { taskId?: string; poolId?: number; keyword?: string; outcome?: string; page?: number; size?: number } = {}) =>
    http.get('/api/workflow/dispatch/logs', { params }).then((r) => r.data),
  timeoutScan: () => http.post('/api/workflow/dispatch/timeout-scan').then((r) => r.data)
}
