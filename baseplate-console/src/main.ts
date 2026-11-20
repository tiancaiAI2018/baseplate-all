import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import formCreate from '@form-create/element-ui'
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker'
import CssWorker from 'monaco-editor/language/css/css.worker.js?worker'
import HtmlWorker from 'monaco-editor/language/html/html.worker.js?worker'
import JsonWorker from 'monaco-editor/language/json/json.worker.js?worker'
import TsWorker from 'monaco-editor/language/typescript/ts.worker.js?worker'
import editorPlugin from '@tmagic/editor'
import MagicElementPlusAdapter from '@tmagic/element-plus-adapter'
import '@tmagic/editor/dist/style.css'
import './style.css'
import App from './App.vue'
import router from './router'

;(globalThis as any).MonacoEnvironment = {
  getWorker(_module: any, label: string) {
    if (label === 'json') return new JsonWorker()
    if (['css', 'scss', 'less'].includes(label)) return new CssWorker()
    if (['html', 'handlebars', 'razor'].includes(label)) return new HtmlWorker()
    if (['typescript', 'javascript'].includes(label)) return new TsWorker()
    return new EditorWorker()
  }
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// 只全局注册动态表单渲染器（<form-create>）；
// 拖拽设计器（@form-create/designer，体积最大的那块）由「流程表单」页自己按需引入，
// 否则整个设计器会被打进首屏主包（实测主包 1.4MB → 2.47MB）
app.use(formCreate)
app.use(editorPlugin, MagicElementPlusAdapter)
app.mount('#app')
