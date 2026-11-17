<template>
  <el-container class="shell">
    <el-aside :width="collapsed ? '76px' : '236px'" class="sidebar">
      <div class="sidebar-brand">
        <div class="logo">B</div>
        <div v-if="!collapsed">
          <div class="name">BASEPLATE</div>
          <div class="ver">业务基盘控制台 · v1.0</div>
        </div>
      </div>
      <div class="sidebar-scroll">
        <el-menu :default-active="route.path" router :collapse="collapsed"
                 background-color="transparent" text-color="#C4D6CF" active-text-color="#ffffff">
          <el-menu-item index="/dashboard">
            <el-icon><Odometer /></el-icon><template #title>概览</template>
          </el-menu-item>
          <el-menu-item-group v-if="!collapsed && auth.has('wf:task')" title="流程中心">
            <el-menu-item index="/workflow/start">
              <el-icon><Promotion /></el-icon><template #title>发起流程</template>
            </el-menu-item>
            <el-menu-item index="/workflow/todo">
              <el-icon><Bell /></el-icon>
              <template #title>
                我的待办
                <el-badge v-if="workflow.todoCount" :value="workflow.todoCount" :max="99" class="menu-badge" />
              </template>
            </el-menu-item>
            <el-menu-item index="/workflow/mine">
              <el-icon><Tickets /></el-icon><template #title>我发起的</template>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group v-if="!collapsed" title="身份与权限">
            <el-menu-item v-if="auth.has('user:view')" index="/identity/users">
              <el-icon><User /></el-icon><template #title>用户管理</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('role:view')" index="/identity/roles">
              <el-icon><Key /></el-icon><template #title>角色权限</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('org:view')" index="/identity/orgs">
              <el-icon><OfficeBuilding /></el-icon><template #title>组织架构</template>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group v-if="!collapsed" title="平台能力">
            <el-menu-item v-if="auth.has('openapi:view')" index="/platform/apps">
              <el-icon><Connection /></el-icon><template #title>开放平台</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('openapi:view')" index="/platform/apis">
              <el-icon><Files /></el-icon><template #title>接口目录</template>
            </el-menu-item>
          </el-menu-item-group>
          <el-menu-item-group v-if="!collapsed" title="治理与配置">
            <el-menu-item v-if="auth.has('audit:view')" index="/audit/logs">
              <el-icon><Document /></el-icon><template #title>审计日志</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('rule:view') || auth.isSuperAdmin" index="/rule/config">
              <el-icon><SetUp /></el-icon><template #title>规则引擎</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('meta:view') || auth.isSuperAdmin" index="/metadata/forms">
              <el-icon><Grid /></el-icon><template #title>低代码配置</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('wf:view') || auth.isSuperAdmin" index="/workflow/designer">
              <el-icon><Share /></el-icon><template #title>流程设计</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('wf:form') || auth.isSuperAdmin" index="/workflow/forms">
              <el-icon><Files /></el-icon><template #title>流程表单</template>
            </el-menu-item>
            <el-menu-item v-if="auth.has('wf:lab') || auth.isSuperAdmin" index="/workflow/lab">
              <el-icon><SetUp /></el-icon><template #title>流程演练</template>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </div>
      <div v-if="!collapsed" class="sidebar-foot">
        {{ auth.user?.tenantId }} · {{ auth.user?.roles?.[0] || 'USER' }}
      </div>
    </el-aside>

    <el-container class="main">
      <el-header class="topbar">
        <div class="topbar-left">
          <el-button text circle @click="collapsed = !collapsed">
            <el-icon :size="18"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>
          <div class="crumb">
            控制台 <span style="margin: 0 6px">/</span> <b>{{ route.meta.title || '概览' }}</b>
          </div>
        </div>
        <div class="topbar-right">
          <el-tag size="small" effect="plain" type="success">{{ auth.user?.tenantId }}</el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <div class="user-chip">
              <div class="avatar">{{ initial }}</div>
              <div style="line-height: 1.25">
                <div style="font-size: 13px; font-weight: 600">{{ auth.user?.displayName || auth.user?.username }}</div>
                <div style="font-size: 11px; color: var(--bp-muted)">{{ auth.user?.roles?.[0] }}</div>
              </div>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">账号信息</el-dropdown-item>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Odometer, User, Key, OfficeBuilding, Connection, Document,
  SetUp, Grid, Share, Fold, Expand, ArrowDown, Files, Promotion, Bell, Tickets
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { useWorkflowStore } from '../stores/workflow'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const workflow = useWorkflowStore()
const collapsed = ref(false)
const initial = computed(() => (auth.user?.displayName || auth.user?.username || 'U').slice(0, 1).toUpperCase())

let todoTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  workflow.refreshTodo()
  todoTimer = setInterval(() => workflow.refreshTodo(), 60000)
})
onBeforeUnmount(() => { if (todoTimer) clearInterval(todoTimer) })
watch(() => auth.user?.userId, () => workflow.refreshTodo())

async function onCommand(command: string) {
  if (command === 'password') {
    router.push('/change-password')
  } else if (command === 'profile') {
    ElMessage.info(`用户：${auth.user?.username}｜租户：${auth.user?.tenantId}｜角色：${(auth.user?.roles || []).join(', ')}`)
  } else if (command === 'logout') {
    await auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.menu-badge { margin-left: 8px; vertical-align: 1px; }
.menu-badge :deep(.el-badge__content) { border: none; background: #D97B3F; }
</style>
