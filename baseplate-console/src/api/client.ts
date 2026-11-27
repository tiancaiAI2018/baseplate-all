import axios from 'axios'

export const TOKEN_KEY = 'bp.console.token'

const http = axios.create({ baseURL: '/', timeout: 20000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  // 调用方显式指定了 Authorization 就不要覆盖：
  // 演练页验证跨租户时要拿另一个租户的令牌发请求，被覆盖后会"看起来能看到"，
  // 检查项因此变成永远通过/永远失败的假结论
  const preset = config.headers?.Authorization
  // 登录请求不能带旧令牌：后端先校验 Authorization，旧令牌失效会让登录直接 401
  const isLogin = (config.url || '').includes('/api/auth/login')
  if (token && !preset && !isLogin) {
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
    if (status === 401) {
      // 登录页也要清：否则失效令牌会一直跟着后续登录请求
      localStorage.removeItem(TOKEN_KEY)
      if (!location.hash.includes('/login')) {
        location.hash = '#/login'
      }
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
