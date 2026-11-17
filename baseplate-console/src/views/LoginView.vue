<template>
  <div class="login-page">
    <section class="login-brand">
      <div class="brand-mark">
        <div class="brand-logo">B</div>
        <div>
          <div class="brand-name">BASEPLATE</div>
          <div class="brand-sub">可复用业务基盘</div>
        </div>
      </div>
      <h1 class="brand-title">一次建设横向能力<br>业务只写纵向逻辑</h1>
      <p class="brand-desc">
        身份权限、开放平台、审计、工作流、规则与低代码配置已经内置；
        业务模块通过声明式清单接入，不重复造轮子。
      </p>
      <div class="brand-features">
        <div class="brand-feature"><b>统一身份与权限</b><span>角色、按钮、数据范围，一套模型</span></div>
        <div class="brand-feature"><b>开放平台双凭证</b><span>Token + HMAC 签名，可管可查</span></div>
        <div class="brand-feature"><b>全链路审计</b><span>访问、业务、登录三类留痕</span></div>
        <div class="brand-feature"><b>可切换基础设施</b><span>端口与适配器隔离，内核零改动</span></div>
      </div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <h2>欢迎回来</h2>
        <p class="sub">登录业务基盘控制台，管理你的模块与权限。</p>
        <el-form :model="form" label-position="top" @submit.prevent="submit">
          <el-form-item label="租户">
            <el-input v-model="form.tenant" size="large" placeholder="default">
              <template #prefix><el-icon><OfficeBuilding /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="form.username" size="large" autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" show-password size="large"
                      autocomplete="current-password" @keyup.enter="submit">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" style="width: 100%; margin-top: 6px"
                     :loading="loading" @click="submit">登录控制台</el-button>
        </el-form>
        <div class="login-tip">
          首次登录或密码过期会强制进入改密页；改密后所有旧令牌立即失效。
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { OfficeBuilding, User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { errorText } from '../api/client'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ tenant: 'default', username: 'admin', password: '' })

/** 第三方跳授权页时用户往往未登录，登录后必须回到授权页，否则同意流程断掉。 */
function afterLogin() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  if (redirect && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/dashboard'
}

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请填写用户名与密码')
    return
  }
  loading.value = true
  try {
    const result = await auth.login(form.username, form.password, form.tenant)
    if (result.mustChangePassword) {
      ElMessage.warning('首次登录或密码已过期，请先修改密码')
      router.push('/change-password')
    } else {
      router.push(afterLogin())
    }
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}
</script>
