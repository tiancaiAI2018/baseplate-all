import { createApp } from 'vue'
import TMagicApp, { DataSourceManager, DeepObservedData } from '@tmagic/core'
import Playground from './playground.vue'
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

const app = new TMagicApp({ ua: window.navigator.userAgent, platform: 'editor' })
app.setDesignWidth(window.document.documentElement.getBoundingClientRect().width)

const components: Record<string, any> = {
  text: Text, button: Button, img: Img, container: Container, page: Page,
  'phone-input': PhoneInput, 'share-button': ShareButton
}
Object.entries(components).forEach(([type, component]) => app.registerComponent(type, component))

const vueApp = createApp(Playground)
vueApp.provide('app', app)
vueApp.config.globalProperties.app = app
vueApp.mount('#app')
