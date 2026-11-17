import { defineStore } from 'pinia'
import http from '../api/client'
import { useAuthStore } from './auth'

export const useWorkflowStore = defineStore('workflow', {
  state: () => ({
    todoCount: 0
  }),
  actions: {
    async refreshTodo() {
      const auth = useAuthStore()
      if (!auth.loggedIn || !auth.has('wf:task')) {
        this.todoCount = 0
        return
      }
      try {
        const { data } = await http.get('/api/workflow/tasks/todo', { params: { page: 1, size: 1 } })
        this.todoCount = data.total || 0
      } catch {
        // 角标失败不影响页面
      }
    }
  }
})