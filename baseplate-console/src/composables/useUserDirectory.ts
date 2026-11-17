import { ref } from 'vue'
import http from '../api/client'

export interface UserOption {
  userId: string
  username: string
  displayName: string
  orgName?: string
}

// 模块级缓存：办理抽屉、进度轨迹、发起配置共用一份人员名录，
// 避免每个组件各拉一次 /assignee-options
const users = ref<UserOption[]>([])
let loading: Promise<void> | null = null

export function useUserDirectory() {
  async function ensureLoaded() {
    if (users.value.length || loading) return loading || Promise.resolve()
    loading = http.get('/api/workflow/assignee-options')
      .then(({ data }) => { users.value = data || [] })
      .catch(() => { users.value = [] })
      .finally(() => { loading = null })
    return loading
  }

  function nameOf(id: any): string {
    if (id == null || id === '') return '—'
    const key = String(id)
    // 历史数据里发起人存的是 userId，表单/流水的 created_by 存的是 username，两种都要能认出来
    const found = users.value.find((item) => String(item.userId) === key || item.username === key)
    return found ? found.displayName : key
  }

  function labelOf(user: UserOption) {
    return user.orgName ? `${user.displayName}（${user.orgName}）` : user.displayName
  }

  return { users, ensureLoaded, nameOf, labelOf }
}