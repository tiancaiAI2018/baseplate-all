import http from './client'

/** 与后端 ExternalToolDescriptor 对齐：端点选用目录（不作为自动注册）。 */
export interface ExternalToolDescriptor {
  code: string
  name: string
  description: string
  serviceCode: string
  endpointCode: string
  readonly: boolean
  paramsSchema: Record<string, any>
  pages: string[]
  permissionCode: string
}

/** 与后端 EndpointView 对齐。 */
export interface IntegrationEndpointView {
  code: string
  path: string
  method: string
  readonly: boolean
  toolCode: string
  toolName: string
  toolDescription: string
  paramsSchema: Record<string, any>
  pages: string[]
  permissionCode: string
  status: string
  sortNo: number
}

/** 与后端 ServiceView 对齐。 */
export interface IntegrationServiceView {
  code: string
  name: string
  baseUrl: string
  authType: string
  credentialHeader: string
  credentialConfigured: boolean
  status: string
  endpoints: IntegrationEndpointView[]
}

/** 服务新增/编辑请求体；编辑时 credential 留空表示保持旧值。 */
export interface IntegrationServicePayload {
  code: string
  name: string
  baseUrl: string
  authType: string
  credentialHeader: string
  credential?: string
  staticHeaders?: Record<string, string>
  status: string
}

/** 端点新增/编辑请求体。 */
export interface IntegrationEndpointPayload {
  code: string
  path: string
  method: string
  readonly: boolean
  toolCode: string
  toolName: string
  toolDescription: string
  paramsSchema: Record<string, any>
  pages: string[]
  permissionCode: string
  status: string
  sortNo: number
}

export const integrationApi = {
  services: () => http.get<IntegrationServiceView[]>('/api/integration/admin/services'),
  createService: (payload: IntegrationServicePayload) =>
    http.post<IntegrationServiceView>('/api/integration/admin/services', payload),
  updateService: (code: string, payload: IntegrationServicePayload) =>
    http.put<IntegrationServiceView>(`/api/integration/admin/services/${encodeURIComponent(code)}`, payload),
  deleteService: (code: string) =>
    http.delete(`/api/integration/admin/services/${encodeURIComponent(code)}`),
  createEndpoint: (serviceCode: string, payload: IntegrationEndpointPayload) =>
    http.post<IntegrationEndpointView>(
      `/api/integration/admin/services/${encodeURIComponent(serviceCode)}/endpoints`, payload),
  updateEndpoint: (serviceCode: string, endpointCode: string, payload: IntegrationEndpointPayload) =>
    http.put<IntegrationEndpointView>(
      `/api/integration/admin/services/${encodeURIComponent(serviceCode)}/endpoints/${encodeURIComponent(endpointCode)}`,
      payload),
  deleteEndpoint: (serviceCode: string, endpointCode: string) =>
    http.delete(
      `/api/integration/admin/services/${encodeURIComponent(serviceCode)}/endpoints/${encodeURIComponent(endpointCode)}`),
  /** 端点选用目录：返回已注册的 ExternalToolDescriptor[]。 */
  registeredServices: () => http.get<ExternalToolDescriptor[]>('/api/integration/proxy/services')
}
