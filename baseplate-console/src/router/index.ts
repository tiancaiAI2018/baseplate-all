import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true, title: '登录' } },
    {
      path: '/oauth/authorize',
      component: () => import('../views/OAuthAuthorizeView.vue'),
      meta: { title: '授权确认' }
    },
    { path: '/change-password', component: () => import('../views/ChangePasswordView.vue'), meta: { title: '修改密码' } },
    {
      path: '/',
      component: () => import('../layout/ConsoleLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '概览' } },
        { path: 'identity/users', component: () => import('../views/UsersView.vue'), meta: { title: '用户管理' } },
        { path: 'identity/roles', component: () => import('../views/RolesView.vue'), meta: { title: '角色权限' } },
        { path: 'identity/orgs', component: () => import('../views/OrgsView.vue'), meta: { title: '组织架构' } },
        { path: 'platform/apps', component: () => import('../views/OpenAppsView.vue'), meta: { title: '开放平台' } },
        { path: 'platform/apis', component: () => import('../views/ApiCatalogView.vue'), meta: { title: '接口目录' } },
        { path: 'audit/logs', component: () => import('../views/AuditView.vue'), meta: { title: '审计日志' } },
        { path: 'rule/config', component: () => import('../views/RulesView.vue'), meta: { title: '规则引擎' } },
        { path: 'metadata/forms', component: () => import('../views/MetadataView.vue'), meta: { title: '低代码配置' } },
        { path: 'workflow/start', component: () => import('../views/ProcessStartView.vue'), meta: { title: '发起流程' } },
        { path: 'workflow/todo', component: () => import('../views/MyTodoView.vue'), meta: { title: '我的待办' } },
        { path: 'workflow/mine', component: () => import('../views/MyInstancesView.vue'), meta: { title: '我发起的' } },
        { path: 'workflow/designer', component: () => import('../views/WorkflowView.vue'), meta: { title: '工作流' } },
        { path: 'workflow/forms', component: () => import('../views/FormDesignerView.vue'), meta: { title: '流程表单' } },
        { path: 'workflow/lab', component: () => import('../views/WorkflowLabView.vue'), meta: { title: '流程演练' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.loggedIn) {
    // 带上原地址：第三方跳授权页时，用户登录后要能回到授权页，否则同意流程断掉
    return { path: '/login', query: to.path === '/login' ? {} : { redirect: to.fullPath } }
  }
  if (!auth.user) {
    try {
      await auth.loadMe()
    } catch {
      return '/login'
    }
  }
  if (auth.user?.mustChangePassword && to.path !== '/change-password') {
    return '/change-password'
  }
  return true
})

export default router
