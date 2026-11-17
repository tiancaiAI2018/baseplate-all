<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1 class="login-title">修改密码</h1>
      <p class="login-sub">首次登录或密码过期时必须修改；修改成功后所有旧令牌立即失效。</p>
      <el-form :model="form" label-position="top">
        <el-form-item label="原密码">
          <el-input v-model="form.oldPassword" type="password" show-password size="large" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" show-password size="large" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirm" type="password" show-password size="large" />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">
          提交并重新登录
        </el-button>
      </el-form>
      <p class="hint" style="margin-top: 16px">
        至少 8 位，含大小写字母与数字，不能包含用户名，不能与最近 5 次密码重复。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

async function submit() {
  if (form.newPassword !== form.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    await http.post('/api/auth/password', {
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    ElMessage.success('密码已修改，请重新登录')
    await auth.logout()
    router.push('/login')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}
</script>
