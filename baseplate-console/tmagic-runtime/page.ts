import { createApp } from 'vue'
import TMagicApp, { DataSourceManager, DeepObservedData } from '@tmagic/core'
import PageRuntime from './page.vue'
import Text from '@tmagic/vue-text'
import Button from '@tmagic/vue-button'
import Img from '@tmagic/vue-img'
import Container from '@tmagic/vue-container'
import Page from '@tmagic/vue-page'
import PhoneInput from './components/PhoneInput.vue'
import ShareButton from './components/ShareButton.vue'
import './animations.css'
import 'element-plus/dist/index.css'
import '@tmagic/core/resetcss.css'

DataSourceManager.registerObservedData(DeepObservedData)

const params = new URLSearchParams(location.search)
const code = params.get('code') || ''
const tenant = params.get('tenant') || 'default'

function unwrap(raw: any) {
  return raw && typeof raw === 'object' && 'dsl' in raw ? raw.dsl : raw
}

async function bootstrap() {
  const response = await fetch(`/api/c/pages/${encodeURIComponent(code)}?tenant=${encodeURIComponent(tenant)}`)
  if (!response.ok) throw new Error('页面不存在或尚未发布')
  const data = await response.json()
  const dsl = unwrap(data.uiSchema)
  ;(window as any).magicDSL = [dsl]

  const app = new TMagicApp({
    ua: window.navigator.userAgent,
    config: dsl,
    curPage: params.get('page') || undefined
  })
  app.setDesignWidth(app.env.isWeb ? window.document.documentElement.getBoundingClientRect().width : 375)

  const components: Record<string, any> = {
    text: Text, button: Button, img: Img, container: Container, page: Page,
    'phone-input': PhoneInput, 'share-button': ShareButton
  }
  Object.entries(components).forEach(([type, component]) => app.registerComponent(type, component))

  const vueApp = createApp(PageRuntime)
  vueApp.provide('app', app)
  vueApp.config.globalProperties.app = app
  vueApp.mount('#app')
}

bootstrap().catch((error) => {
  document.getElementById('app')!.innerHTML = `<div style="padding:40px;text-align:center;color:#64748b">${error.message}</div>`
})
