import { defineStore } from 'pinia'
import http, { TOKEN_KEY } from '../api/client'

export interface CurrentUser {
  userId: string
  tenantId: string
  username: string
  displayName?: string
  roles: string[]
  permissions: string[]
  mustChangePassword: boolean
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null as CurrentUser | null,
    menus: [] as Array<{ path: string; label: string; permission?: string }>
  }),
  getters: {
    loggedIn: (state) => !!state.token,
    isSuperAdmin: (state) => !!state.user?.roles?.includes('SUPER_ADMIN')
  },
  actions: {
    async login(username: string, password: string, tenant: string) {
      const { data } = await http.post('/api/auth/login', { username, password, tenant })
      this.token = data.accessToken
      localStorage.setItem(TOKEN_KEY, data.accessToken)
      await this.loadMe()
      return data
    },
    async loadMe() {
      const { data } = await http.get('/api/auth/me')
      this.user = data
      try {
        const menus = await http.get('/api/identity/menus')
        this.menus = menus.data
      } catch {
        this.menus = []
      }
    },
    async logout() {
      try {
        await http.post('/api/auth/logout')
      } catch {
        // 令牌可能已过期，本地清理仍然要做
      }
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    },
    has(permission: string) {
      return this.isSuperAdmin || !!this.user?.permissions?.includes(permission)
    }
  }
})
