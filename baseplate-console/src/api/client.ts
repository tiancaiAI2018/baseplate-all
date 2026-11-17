import axios from 'axios'

export const TOKEN_KEY = 'bp.console.token'

const http = axios.create({ baseURL: '/', timeout: 20000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  // 调用方显式指定了 Authorization 就不要覆盖：
  // 演练页验证跨租户时要拿另一个租户的令牌发请求，被覆盖后会"看起来能看到"，
  // 检查项因此变成永远通过/永远失败的假结论
  const preset = config.headers?.Authorization
  if (token && !preset) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const code = error?.response?.data?.code
    if (status === 401 && !location.hash.includes('/login')) {
      localStorage.removeItem(TOKEN_KEY)
      location.hash = '#/login'
    }
    if (status === 403 && code === 'PASSWORD_CHANGE_REQUIRED' && !location.hash.includes('/change-password')) {
      location.hash = '#/change-password'
    }
    return Promise.reject(error)
  }
)

export function errorText(error: any): string {
  return error?.response?.data?.detail || error?.response?.data?.title || error?.message || '请求失败'
}

export default http
