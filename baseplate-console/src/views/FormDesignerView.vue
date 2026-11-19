<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">表单设计</h2>
        <p class="page-desc">
          拖拽生成表单，保存为可复用组件素材；表单发布后可以被任意流程节点绑定，也能在别的页面单独渲染。
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Back" @click="backToList">返回列表</el-button>
        <el-button @click="preview">预览</el-button>
        <el-button @click="saveDraft">保存草稿</el-button>
        <el-button type="primary" @click="publish">保存并发布</el-button>
      </div>
    </div>

    <div class="card" style="margin-bottom: 14px">
      <div class="toolbar">
        <el-select v-model="currentCode" placeholder="选择已有表单" clearable filterable
                   style="width: 240px" @change="loadForm">
          <el-option v-for="item in forms" :key="item.code"
                     :label="`${item.name || item.code}（v${item.version} · ${item.status}）`"
                     :value="item.code" />
        </el-select>
        <el-input v-model="code" placeholder="表单编码，如 leave_form" style="width: 220px" />
        <el-input v-model="name" placeholder="表单名称" style="width: 200px" />
        <span class="hint">当前共 {{ fields.length }} 个字段 · {{ actions.length }} 条联动动作</span>
      </div>
    </div>

    <el-row :gutter="14">
      <el-col :span="5">
        <div class="card side-card">
          <h3 class="card-title"><span class="dot"></span>组件素材库</h3>
          <p class="hint">把好看的片段存起来，之后一键插入任何表单，或者单独在其他页面渲染。</p>
          <div v-for="widget in widgets" :key="widget.code" class="widget-item">
            <div><b>{{ widget.name }}</b><div class="hint">{{ widget.code }} · {{ widget.category }}</div></div>
            <div class="widget-actions">
              <el-button link type="primary" size="small" @click="insertWidget(widget)">插入</el-button>
              <el-button link size="small" @click="viewWidget(widget)">复用</el-button>
            </div>
          </div>
          <el-empty v-if="!widgets.length" description="还没有素材" :image-size="56" />
          <el-button style="width: 100%; margin-top: 10px" @click="saveAsWidget">把当前表单存为素材</el-button>
        </div>
        <div class="card side-card" style="margin-top: 14px">
          <h3 class="card-title"><span class="dot"></span>联动动作（受控）</h3>
          <p class="hint">字段满足条件时显示/隐藏目标字段。只提供结构化动作，不允许写脚本。</p>
          <div v-for="(action, index) in actions" :key="index" class="action-row">
            <el-input v-model="action.when.field" placeholder="来源字段" style="width: 96px" />
            <el-select v-model="action.when.operator" style="width: 74px">
              <el-option v-for="op in ['>', '>=', '<', '<=', '==', '!=']" :key="op" :label="op" :value="op" />
            </el-select>
            <el-input v-model="action.when.value" placeholder="值" style="width: 64px" />
            <el-input v-model="action.then.field" placeholder="目标字段" style="width: 96px" />
            <el-button link type="danger" @click="actions.splice(index, 1)">删</el-button>
          </div>
          <el-button style="width: 100%; margin-top: 8px" @click="addAction">添加动作</el-button>
        </div>
      </el-col>
      <el-col :span="19">
        <div class="card designer-card">
          <FcDesigner ref="designerRef" height="600px" />
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="previewOpen" title="表单预览 / 素材复用" width="720px">
      <FormRenderer :rule="previewRule" />
      <template #footer><el-button @click="previewOpen = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import FcDesigner from '@form-create/designer'
import '@form-create/designer/src/style/index.css'
import http, { errorText } from '../api/client'
import { registerAgentCanvas } from '../api/agentCanvas'
import FormRenderer from './FormRenderer.vue'

const route = useRoute()
const router = useRouter()
const designerRef = ref<any>(null)
const forms = ref<any[]>([])
const widgets = ref<any[]>([])
const currentCode = ref('')
const code = ref('')
const name = ref('')
const actions = ref<any[]>([])
const previewOpen = ref(false)
const previewRule = ref<any[]>([])
let disposeCanvasProvider: (() => void) | null = null

const TYPE_MAP: Record<string, string> = {
  input: 'STRING', textarea: 'TEXT', inputNumber: 'NUMBER', slider: 'NUMBER',
  datePicker: 'DATE', timePicker: 'DATETIME', switch: 'BOOLEAN',
  select: 'SELECT', radio: 'SELECT', checkbox: 'MULTI_SELECT', cascader: 'MULTI_SELECT',
  upload: 'FILE', editor: 'RICH_TEXT'
}

function currentRule(): any[] {
  try { return designerRef.value?.getRule?.() || [] } catch { return [] }
}
function currentOption(): any {
  try { return designerRef.value?.getOption?.() || {} } catch { return {} }
}
const fields = computed(() => currentRule().filter((item: any) => item?.field))

function buildSchema(): string {
  return JSON.stringify({
    fields: fields.value.map((item: any) => ({
      name: item.field,
      title: item.title || item.field,
      type: TYPE_MAP[item.type] || 'STRING'
    }))
  })
}
function buildUiSchema(): string {
  return JSON.stringify({ rule: currentRule(), option: currentOption(), actions: actions.value })
}

async function loadForms() {
  try {
    const { data } = await http.get('/api/metadata/FORM')
    forms.value = data
  } catch { forms.value = [] }
}
async function loadWidgets() {
  try {
    const { data } = await http.get('/api/workflow/widgets')
    widgets.value = data
  } catch { widgets.value = [] }
}

async function loadForm(selected: string) {
  if (!selected) return
  currentCode.value = selected
  try {
    const { data } = await http.get(`/api/metadata/FORM/${selected}`)
    code.value = data.code
    name.value = data.name || data.code
    let ui: any = {}
    try { ui = JSON.parse(data.uiSchemaJson || '{}') } catch { ui = {} }
    designerRef.value?.setRule?.(Array.isArray(ui) ? ui : (ui.rule || []))
    if (!Array.isArray(ui) && ui.option) designerRef.value?.setOption?.(ui.option)
    actions.value = Array.isArray(ui.actions) ? ui.actions : []
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function saveDraft(): Promise<any> {
  if (!code.value) { ElMessage.warning('请填写表单编码'); return null }
  if (!fields.value.length) { ElMessage.warning('请先拖入至少一个带字段的控件'); return null }
  try {
    const { data } = await http.post(`/api/metadata/FORM/${code.value}`, {
      name: name.value, schema: buildSchema(), uiSchema: buildUiSchema()
    })
    ElMessage.success(`已保存草稿 v${data.version}`)
    await loadForms()
    return data
  } catch (error) {
    ElMessage.error(errorText(error))
    return null
  }
}

async function publish() {
  const saved = await saveDraft()
  if (!saved) return
  try {
    await http.post(`/api/metadata/FORM/${code.value}/publish`, {}, { params: { version: saved.version } })
    ElMessage.success(`v${saved.version} 已发布，流程节点现在可以绑定它`)
    await loadForms()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function preview() {
  previewRule.value = currentRule()
  previewOpen.value = true
}

function addAction() {
  actions.value.push({ when: { field: '', operator: '>', value: '' }, then: { type: 'SHOW', field: '' } })
}

async function saveAsWidget() {
  try {
    const { value } = await ElMessageBox.prompt('素材编码（英文，之后可复用）', '保存为组件素材',
      { inputValue: 'widget_' + Date.now().toString().slice(-6) })
    await http.post('/api/workflow/widgets', {
      code: value, name: name.value + ' · 素材', category: 'CUSTOM',
      ruleJson: JSON.stringify(currentRule()), description: '由表单设计器保存'
    })
    ElMessage.success('已存入素材库')
    await loadWidgets()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

function parseWidget(widget: any): any[] {
  try {
    const value = JSON.parse(widget.rule_json || '[]')
    return Array.isArray(value) ? value : (value.rule || [])
  } catch { return [] }
}
function insertWidget(widget: any) {
  designerRef.value?.setRule?.([...currentRule(), ...parseWidget(widget)])
  ElMessage.success(`已插入素材「${widget.name}」`)
}
function viewWidget(widget: any) {
  previewRule.value = parseWidget(widget)
  previewOpen.value = true
}

/** AI 保存草稿后会带 code 跳到本页；已经在本页时靠 v 时间戳触发重新载入。 */
async function loadFromRoute() {
  const next = String(route.query.code || '')
  if (!next) {
    resetNewForm()
    return
  }
  currentCode.value = next
  await loadForm(next)
}

/** 新增模式：清空编码、名称和画布，避免把上一个表单的内容带进来。 */
function resetNewForm() {
  code.value = ''
  name.value = ''
  currentCode.value = ''
  actions.value = []
  designerRef.value?.setRule?.([])
}

function backToList() {
  router.push('/workflow/forms')
}

onMounted(async () => {
  // AI 助手读取的是设计器当前内容（含未保存修改），不是已保存版本
  disposeCanvasProvider = registerAgentCanvas('workflow/forms/design', () => ({
    available: true,
    data: {
      code: code.value,
      name: name.value,
      fields: fields.value.map((item: any) => ({
        field: item.field,
        title: item.title || item.field,
        type: item.type,
        options: Array.isArray(item.options) ? item.options : undefined
      }))
    }
  }))
  await Promise.allSettled([loadForms(), loadWidgets()])
  await loadFromRoute()
})

onBeforeUnmount(() => {
  disposeCanvasProvider?.()
  disposeCanvasProvider = null
})

watch(() => [route.query.code, route.query.v], () => {
  loadFromRoute()
})
</script>

<style scoped>
.designer-card { padding: 0; overflow: hidden; }
.side-card { box-shadow: none; }
.widget-item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px solid #EBEFEC; }
.widget-actions { display: flex; gap: 2px; flex: none; }
.action-row { display: flex; gap: 4px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
</style>
