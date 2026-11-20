<template>
  <div class="cend-tmagic-designer">
    <div class="designer-head">
      <div class="head-left">
        <el-button text @click="$router.push('/cend/pages')">返回</el-button>
        <el-input v-model="code" placeholder="页面编码" style="width: 220px" />
        <el-input v-model="name" placeholder="页面名称" style="width: 220px" />
        <span class="hint">版本 {{ version }}</span>
      </div>
      <div class="head-right">
        <el-button @click="preview">预览</el-button>
        <el-button type="primary" @click="publish">保存并发布</el-button>
      </div>
    </div>
    <TMagicEditor
      ref="editorRef"
      v-model="dsl"
      :menu="menu"
      runtime-url="/tmagic-runtime/playground.html"
      :component-group-list="componentGroupList"
      :props-configs="propsConfigs"
      :props-values="propsValues"
      :event-method-list="eventMethodList"
      :stage-rect="stageRect"
      :default-selected="selectedId"
      :auto-scroll-into-view="true"
    />
    <el-dialog v-model="previewOpen" title="页面预览" width="980px" destroy-on-close>
      <iframe :src="previewUrl" frameborder="0" style="width: 100%; height: 640px; background: #fff" />
      <template #footer><el-button @click="previewOpen = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { FolderOpened, Tickets, SwitchButton, PictureFilled, Files, Box, Iphone, Share } from '@element-plus/icons-vue'
import { TMagicEditor } from '@tmagic/editor'
import { NodeType, type MApp } from '@tmagic/core'
import { config as textConfig, value as textValue, event as textEvent } from '@tmagic/vue-text'
import { config as buttonConfig, value as buttonValue, event as buttonEvent } from '@tmagic/vue-button'
import { config as imgConfig, value as imgValue, event as imgEvent } from '@tmagic/vue-img'
import { config as containerConfig, value as containerValue, event as containerEvent } from '@tmagic/vue-container'
import { config as pageConfig, value as pageValue, event as pageEvent } from '@tmagic/vue-page'
import { toPng } from 'html-to-image'
import http, { errorText } from '../api/client'
import { registerAgentCanvas } from '../api/agentCanvas'
import { registerCendDesigner } from '../api/cendDesigner'

const route = useRoute()
const router = useRouter()
const editorRef = ref<any>(null)
const code = ref(String(route.query.code || ''))
const name = ref('')
const version = ref(0)
const previewOpen = ref(false)
const selectedId = ref('')

function emptyDsl(): MApp {
  return {
    id: 'cend_app',
    name: 'C端页面',
    type: NodeType.ROOT,
    items: [{
      id: 'page_home',
      name: 'index',
      type: NodeType.PAGE,
      layout: 'absolute',
      style: { position: 'relative', width: '100%', height: '100%', backgroundColor: '#ffffff' },
      items: []
    }]
  }
}

const dsl = ref<MApp>(emptyDsl())
const stageRect = reactive({ width: 1200, height: 900 })

const componentGroupList = [
  { title: '基础组件', items: [
    { icon: Tickets, text: '文本', type: 'text' },
    { icon: SwitchButton, text: '按钮', type: 'button' },
    { icon: PictureFilled, text: '图片', type: 'img' }
  ] },
  { title: '布局组件', items: [
    { icon: FolderOpened, text: '容器', type: 'container' },
    { icon: Box, text: '页面', type: 'page' }
  ] },
  { title: '表单组件', items: [
    { icon: Iphone, text: '手机号输入', type: 'phone-input' },
    { icon: Share, text: '分享按钮', type: 'share-button' }
  ] },
  { title: '组合', items: [
    { icon: Files, text: '标题块', data: {
      type: 'container', name: '标题块',
      style: { position: 'relative', width: '100%', height: '120px', padding: '24px' },
      items: [{ type: 'text', name: '标题', text: '输入标题', style: { fontSize: '28px', fontWeight: '700', textAlign: 'center' } }]
    } }
  ] }
] as any[]

const propsConfigs: Record<string, any> = {
  text: textConfig, button: buttonConfig, img: imgConfig,
  container: containerConfig, page: pageConfig,
  'phone-input': [
    { name: 'placeholder', text: '占位提示' },
    { name: 'prefix', text: '前缀文字' },
    { name: 'value', text: '默认值' }
  ],
  'share-button': [{ name: 'text', text: '按钮文字' }]
}
const propsValues: Record<string, any> = {
  text: textValue, button: buttonValue, img: imgValue,
  container: containerValue, page: pageValue,
  'phone-input': { placeholder: '请输入手机号', prefix: '', value: '' },
  'share-button': { text: '分享活动链接' }
}
const eventMethodList: Record<string, any> = {
  text: textEvent, button: buttonEvent, img: imgEvent,
  container: containerEvent, page: pageEvent,
  'phone-input': { events: [], methods: [] },
  'share-button': { events: [], methods: [] }
}

const menu = computed(() => ({
  left: [{ type: 'text', text: 'C端页面设计' }],
  center: ['delete', 'undo', 'redo', 'guides', 'rule', 'zoom'],
  right: [
    { type: 'button', text: '保存', handler: () => void saveDraft() },
    { type: 'button', text: '发布', handler: () => void publish() }
  ]
}))

const previewUrl = computed(() =>
  `/tmagic-runtime/page.html?code=${encodeURIComponent(code.value)}&v=${version.value}`
)

function normalize(raw: any): MApp {
  if (!raw) return emptyDsl()
  const candidate = raw.dsl || raw
  return candidate?.items && Array.isArray(candidate.items) ? candidate as MApp : emptyDsl()
}

async function load(target = code.value) {
  if (!target) return
  try {
    const { data } = await http.get(`/api/metadata/CEND_PAGE/${target}`)
    code.value = data.code
    name.value = data.name || data.code
    version.value = data.version
    const stored = data.uiSchemaJson ?? data.uiSchema
    const raw = typeof stored === 'string' ? JSON.parse(stored) : stored
    dsl.value = normalize(raw)
    selectedId.value = dsl.value.items?.[0]?.id || ''
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function saveDraft() {
  if (!code.value) {
    ElMessage.warning('请先填写页面编码')
    return false
  }
  try {
    const { data } = await http.post(`/api/metadata/CEND_PAGE/${code.value}`, {
      name: name.value || code.value,
      schema: { fields: [] },
      uiSchema: { version: 2, dsl: dsl.value }
    })
    version.value = data.version
    editorRef.value?.editorService?.resetModifiedNodeId?.()
    ElMessage.success(`草稿已保存，版本 v${data.version}`)
    return true
  } catch (error) {
    ElMessage.error(errorText(error))
    return false
  }
}

async function publish() {
  if (!await saveDraft()) return
  try {
    await http.post(`/api/metadata/CEND_PAGE/${code.value}/publish`, null, { params: { version: version.value } })
    ElMessage.success('已发布')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function preview() {
  void saveDraft().then((ok) => { if (ok) previewOpen.value = true })
}

watch(
  () => [route.query.code, route.query.v],
  ([value]) => { if (value) void load(String(value)) }
)

async function screenshot() {
  const iframe = document.querySelector('iframe[src*="playground"]') as HTMLIFrameElement | null
  const body = iframe?.contentDocument?.body
  if (!body) return ''
  return await toPng(body, { backgroundColor: '#0b1020', pixelRatio: 1, cacheBust: true })
}

let unregisterCanvas: (() => void) | null = null
let unregisterDesigner: (() => void) | null = null
onMounted(() => {
  if (!code.value) {
    code.value = `page_${Date.now().toString(36)}`
    name.value = '未命名 C 端页面'
  }
  void load()
  unregisterCanvas = registerAgentCanvas('cend/pages/design', () => ({
    available: true,
    data: { code: code.value, name: name.value, dsl: dsl.value }
  }))
  unregisterDesigner = registerCendDesigner({
    code: () => code.value,
    getDsl: () => dsl.value,
    setDsl: (value) => { dsl.value = value },
    screenshot,
    selectedId: () => selectedId.value
  })
})

onBeforeUnmount(() => {
  unregisterCanvas?.()
  unregisterDesigner?.()
})
</script>

<style scoped>
.cend-tmagic-designer { height: calc(100vh - 120px); display: flex; flex-direction: column; gap: 8px; }
.designer-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.head-left, .head-right { display: flex; align-items: center; gap: 8px; }
.hint { color: var(--bp-muted); font-size: 12px; }
.cend-tmagic-designer :deep(.m-editor) { flex: 1; min-height: 0; border: 1px solid var(--bp-line, #e5e7eb); }
</style>
