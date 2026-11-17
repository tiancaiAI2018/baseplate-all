<template>
  <div class="authorize-page">
    <div class="authorize-card">
      <div class="brand-line">
        <div class="brand-logo">B</div>
        <span>BASEPLATE</span>
      </div>

      <div v-if="loading" class="center-block">
        <el-skeleton :rows="5" animated />
      </div>

      <div v-else-if="fatal" class="center-block">
        <el-result icon="error" title="无法完成授权" :sub-title="fatal">
          <template #extra>
            <el-button @click="closeTab">关闭页面</el-button>
          </template>
        </el-result>
      </div>

      <template v-else>
        <h2 class="auth-title">授权确认</h2>
        <p class="auth-sub">
          <b>{{ context.appName }}</b> 请求访问你在本系统内的账号
        </p>

        <div class="app-box">
          <div class="app-avatar">{{ (context.appName || 'A').slice(0, 1) }}</div>
          <div>
            <div class="app-name">{{ context.appName }}</div>
            <div class="mono hint">{{ context.clientId }}</div>
          </div>
          <el-tag v-if="context.previouslyGranted" size="small" type="success" effect="plain">
            之前授权过
          </el-tag>
        </div>

        <div class="scope-title">该应用将获得以下权限</div>
        <ul class="scope-list">
          <li v-for="item in context.scopeDescriptions" :key="item.scope">
            <span class="scope-dot"></span>
            <div>
              <div class="scope-label">{{ item.label }}</div>
              <div class="mono hint">{{ item.scope }}</div>
            </div>
          </li>
          <li v-if="!context.scopeDescriptions?.length" class="hint">该应用未申请额外权限</li>
        </ul>

        <el-alert v-if="!context.tenantOk" type="error" :closable="false" show-icon
                  title="该应用不属于你所在的租户，不能授权" style="margin-top: 14px" />
        <el-alert v-else-if="!context.redirectUriAllowed" type="error" :closable="false" show-icon
                  title="回调地址不在该应用的白名单内，已拒绝" style="margin-top: 14px" />
        <el-alert v-else-if="!context.authorizeSupported" type="error" :closable="false" show-icon
                  title="该应用未开通授权码流程，无法授权" style="margin-top: 14px" />
        <el-alert v-else type="warning" :closable="false" show-icon
                  title="授权后该应用可以代表你调用上述接口，操作记录会记在你名下" style="margin-top: 14px" />

        <div class="who">
          当前登录：<b>{{ context.currentUser }}</b>
        </div>

        <div class="actions">
          <el-button size="large" :loading="submitting" @click="decide(false)">拒绝</el-button>
          <el-button size="large" type="primary" :disabled="!canApprove"
                     :loading="submitting" @click="decide(true)">
            同意授权
          </el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'

const route = useRoute()
const loading = ref(true)
const submitting = ref(false)
const fatal = ref('')
const context = reactive<any>({
  appName: '', clientId: '', requestedScopes: [], scopeDescriptions: [],
  redirectUriAllowed: false, tenantOk: false, authorizeSupported: true,
  previouslyGranted: false, currentUser: ''
})

const clientId = computed(() => String(route.query.client_id || route.query.clientId || ''))
const redirectUri = computed(() => String(route.query.redirect_uri || route.query.redirectUri || ''))
const scope = computed(() => String(route.query.scope || ''))
const state = computed(() => String(route.query.state || ''))
const responseType = computed(() => String(route.query.response_type || 'code'))

const canApprove = computed(() =>
  context.tenantOk && context.redirectUriAllowed && context.authorizeSupported)

async function load() {
  if (!clientId.value) {
    fatal.value = '缺少 client_id 参数'
    loading.value = false
    return
  }
  if (responseType.value !== 'code') {
    fatal.value = `不支持的 response_type：${responseType.value}，本平台只支持 code`
    loading.value = false
    return
  }
  try {
    const { data } = await http.get('/api/platform/oauth/authorize/context', {
      params: { clientId: clientId.value, scope: scope.value, redirectUri: redirectUri.value }
    })
    Object.assign(context, data)
  } catch (error) {
    fatal.value = errorText(error)
  } finally {
    loading.value = false
  }
}

async function decide(approve: boolean) {
  submitting.value = true
  try {
    const { data } = await http.post('/api/platform/oauth/authorize', {
      clientId: clientId.value,
      redirectUri: redirectUri.value,
      scope: scope.value,
      state: state.value,
      approve
    })
    // 直接跳出 SPA，回到第三方登记的回调地址
    window.location.href = data.redirectUrl
  } catch (error) {
    ElMessage.error(errorText(error))
    submitting.value = false
  }
}

function closeTab() {
  window.close()
  ElMessage.info('可以手动关闭此页面')
}

onMounted(load)
</script>

<style scoped>
.authorize-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: linear-gradient(160deg, #EEF5F1 0%, #F6F4EF 55%, #EFF5F2 100%);
}
.authorize-card {
  width: 100%;
  max-width: 540px;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 18px 48px rgba(23, 86, 74, .13);
  padding: 30px 34px 26px;
}
.brand-line { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; }
.brand-logo {
  width: 30px; height: 30px; border-radius: 9px; background: #17564A; color: #fff;
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 15px;
}
.brand-line span { font-size: 12px; letter-spacing: .16em; color: #6B7671; font-weight: 600; }
.auth-title { font-size: 22px; font-weight: 700; color: #1C1B18; margin: 0 0 6px; }
.auth-sub { font-size: 14px; color: #4B5550; margin: 0 0 18px; line-height: 1.7; }
.auth-sub b { color: #17564A; }
.app-box {
  display: flex; align-items: center; gap: 12px; padding: 14px 16px;
  border: 1px solid #DCE7E1; border-radius: 12px; background: #F7FAF8;
}
.app-avatar {
  width: 38px; height: 38px; border-radius: 10px; background: #E4EFEA; color: #17564A;
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 16px;
}
.app-name { font-weight: 600; font-size: 15px; color: #1C1B18; }
.scope-title { font-size: 13px; font-weight: 600; color: #4B5550; margin: 20px 0 8px; }
.scope-list { list-style: none; padding: 0; margin: 0; }
.scope-list li {
  display: flex; gap: 10px; align-items: flex-start;
  padding: 9px 0; border-bottom: 1px dashed #E6EDE9;
}
.scope-list li:last-child { border-bottom: none; }
.scope-dot {
  width: 7px; height: 7px; border-radius: 50%; background: #2E7D4F; margin-top: 7px; flex: none;
}
.scope-label { font-size: 14px; color: #1C1B18; line-height: 1.6; }
.who { font-size: 12px; color: #6B7671; margin-top: 16px; }
.actions { display: flex; gap: 10px; margin-top: 18px; }
.actions .el-button { flex: 1; }
.center-block { padding: 20px 0 10px; }
</style>
