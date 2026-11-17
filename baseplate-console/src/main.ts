import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import formCreate from '@form-create/element-ui'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// 只全局注册动态表单渲染器（<form-create>）；
// 拖拽设计器（@form-create/designer，体积最大的那块）由「流程表单」页自己按需引入，
// 否则整个设计器会被打进首屏主包（实测主包 1.4MB → 2.47MB）
app.use(formCreate)
app.mount('#app')
