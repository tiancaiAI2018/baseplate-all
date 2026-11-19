<template>
  <div class="node-panel">
    <div class="panel-head">
      <b>节点属性</b>
      <span class="hint">点节点逐个配置，部署时一起编译进流程</span>
    </div>
    <div ref="listRef" class="node-list">
      <div v-for="node in nodes" :key="node.id" class="node-row"
           :class="{ active: node.id === currentId }" @click="select(node)">
        <span class="node-type" :class="node.type">{{ typeLabel(node.type) }}</span>
        <span class="node-name">{{ node.name || node.id }}</span>
        <span v-if="configured(node.id)" class="dot-ok"></span>
      </div>
      <el-empty v-if="!nodes.length" description="画布里还没有节点" :image-size="50" />
    </div>
    <div v-if="nodes.length && !config" class="panel-placeholder">
      点上面任意一个节点，开始配它的人、签法和表单
    </div>

    <div v-if="config" class="node-form">
      <el-form label-width="86px" size="small">
        <el-form-item v-if="config.nodeType === 'userTask'" label="找人逻辑">
          <el-select v-model="config.assigneeType" style="width: 100%">
            <el-option v-for="item in options.assigneeTypes || []" :key="item.value"
                       :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="config.nodeType === 'userTask'" label="找人参数">
          <el-select v-if="config.assigneeType === 'ROLE'" v-model="config.assigneeValue" style="width: 100%">
            <el-option v-for="role in options.roles || []" :key="role.code"
                       :label="role.name" :value="role.code" />
          </el-select>
          <el-input v-else v-model="config.assigneeValue"
                    :placeholder="valueHint(config.assigneeType)" />
        </el-form-item>
        <el-form-item v-if="config.nodeType === 'userTask'" label="签署方式">
          <el-select v-model="config.signType" style="width: 100%">
            <el-option v-for="item in options.signTypes || []" :key="item.value"
                       :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isMulti(config)" label="通过比例">
          <el-input-number v-model="config.signRatio" :min="1" :max="100" />
          <span class="hint" style="margin-left: 8px">% 的成员同意即通过</span>
        </el-form-item>
        <el-form-item v-if="config.nodeType === 'userTask'" label="绑定表单">
          <div class="form-bind">
            <div v-for="item in effectiveForms" :key="item.code" class="form-bind-row">
              <el-tag :type="item.base ? 'info' : 'success'" size="small" effect="plain">
                {{ item.base ? '基础' : '新增' }}
              </el-tag>
              <span class="form-bind-name" :title="`${item.name}（${item.code}）`">
                {{ item.name }}<span class="hint">（{{ item.code }}）</span>
              </span>
              <span v-if="item.base" class="hint">自动继承</span>
              <el-button v-else link type="danger" size="small" @click="removeAddedForm(item.code)">移除</el-button>
            </div>
            <el-select v-model="formToAdd" filterable clearable placeholder="＋ 添加本节点专属表单"
                       style="width: 100%" @change="addForm">
              <el-option v-for="form in addableForms" :key="form.code" :value="form.code"
                         :label="`${form.name || form.code}（v${form.version}）`" />
            </el-select>
            <p class="hint" style="margin: 4px 0 0">
              基础表单来自流程发起配置，所有节点共享、节点端不可删除；这里只能追加本节点专属表单。
            </p>
          </div>
        </el-form-item>

        <template v-if="config.nodeType === 'userTask' && effectiveForms.length">
          <div class="sub-title">
            字段权限
            <el-tooltip content="按表单分别设置本节点每个字段是可编辑、只读还是隐藏。服务端会强制裁剪，前端置灰/隐藏只是体验。"
                        placement="top">
              <span class="hint" style="font-weight: 400">（?）</span>
            </el-tooltip>
          </div>
          <el-radio-group v-model="activeFormCode" size="small" class="form-tabs">
            <el-radio-button v-for="item in effectiveForms" :key="item.code" :value="item.code">
              {{ item.name }}{{ item.base ? ' · 基础' : '' }}
            </el-radio-button>
          </el-radio-group>
          <div class="batch-row">
            <span class="hint">当前表单一键设置：</span>
            <el-button size="small" @click="setAllPolicy('EDITABLE')">全可编辑</el-button>
            <el-button size="small" @click="setAllPolicy('READONLY')">全只读</el-button>
            <el-button size="small" @click="setAllPolicy('HIDDEN')">全隐藏</el-button>
          </div>
          <template v-if="fieldPolicyModel[activeFormCode]">
            <div v-if="!formFields.length" class="hint field-empty">该表单没有字段，或尚未发布</div>
            <div v-for="field in formFields" :key="field.name" class="field-policy-row">
              <span class="field-label" :title="field.name">{{ field.title }}</span>
              <el-select v-model="fieldPolicyModel[activeFormCode][field.name]" style="width: 132px"
                         @change="syncFieldPolicy">
                <el-option label="可编辑" value="EDITABLE" />
                <el-option label="只读" value="READONLY" />
                <el-option label="隐藏" value="HIDDEN" />
              </el-select>
            </div>
          </template>
          <div class="hint field-tip">未列出的字段默认为「可编辑」；设置后仅影响本节点。</div>
        </template>

        <template v-if="config.nodeType === 'userTask'">
          <div class="sub-title">批量配置</div>
          <div class="button-row">
            <el-button size="small" :disabled="!prevNode" @click="copyFromPrevious">
              复制「{{ prevNode ? (prevNode.name || prevNode.id) : '上一节点' }}」的表单与权限
            </el-button>
          </div>
          <div class="button-row">
            <el-select v-model="syncTargets" multiple filterable collapse-tags collapse-tags-tooltip
                       placeholder="选择要同步到的其他节点" style="flex: 1; min-width: 0">
              <el-option v-for="node in otherNodes" :key="node.id" :value="node.id"
                         :label="node.name || node.id" />
            </el-select>
            <el-button size="small" type="primary" :disabled="!syncTargets.length" @click="syncToNodes">
              同步
            </el-button>
          </div>
          <div class="hint field-tip">复制/同步只覆盖「表单绑定 + 字段权限」，不动找人、签法和按钮。</div>
        </template>

        <el-form-item v-if="config.nodeType === 'userTask'" label="触发动作">
          <el-select v-model="trigger" clearable style="width: 100%" @change="applyTrigger">
            <el-option v-for="item in options.triggers || []" :key="item.value"
                       :label="item.label" :value="item.value" />
          </el-select>
          <span class="hint" style="margin-left: 4px">只落库，执行器下一轮接</span>
        </el-form-item>
        <el-form-item v-if="config.nodeType === 'userTask'" label="超时(分)">
          <el-input-number v-model="config.timeoutMinutes" :min="0" :max="10080" />
        </el-form-item>

        <template v-if="config.nodeType === 'userTask'">
          <div class="sub-title">决策按钮（是否必填原因可在流程里配置）</div>
          <div v-for="(button, index) in buttons" :key="index" class="button-row">
            <el-select v-model="button.code" style="width: 92px">
              <el-option v-for="item in options.decisionCodes || []" :key="item.value"
                         :label="item.label" :value="item.value" />
            </el-select>
            <el-input v-model="button.label" placeholder="按钮文案" style="width: 86px" />
            <el-checkbox v-model="button.reasonRequired">必填原因</el-checkbox>
            <el-input-number v-model="button.reasonMinLength" :min="0" :max="200" controls-position="right"
                             style="width: 88px" />
            <el-button link type="danger" @click="buttons.splice(index, 1)">删</el-button>
          </div>
          <el-button size="small" style="width: 100%" @click="addButton">添加按钮</el-button>
        </template>

        <template v-if="config.nodeType === 'sequenceFlow'">
          <div class="sub-title">出线条件</div>
          <el-checkbox v-model="rejectRoute" style="margin-bottom: 8px">
            作为驳回路由
            <el-tooltip content="勾选后，这条线只在用户点驳回时按下面的条件选目标；正常审批不会走它。不写条件表示无条件命中。"
                        placement="top">
              <span class="hint" style="font-weight: 400">（?）</span>
            </el-tooltip>
          </el-checkbox>
          <div v-if="rejectRoute" class="hint" style="margin-bottom: 8px">
            驳回时才会求值这条线的条件，命中就退到线尾指向的节点；没有线路命中则退回上一个已完成任务。
          </div>
          <el-radio-group v-model="conditionMode" size="small" style="margin-bottom: 6px">
            <el-radio-button value="STRUCTURED">简单条件</el-radio-button>
            <el-radio-button value="EXPRESSION">表达式</el-radio-button>
          </el-radio-group>
          <div v-if="conditionMode === 'STRUCTURED'" class="button-row">
            <el-input v-model="condition.field" placeholder="字段" style="width: 92px" />
            <el-select v-model="condition.operator" style="width: 78px">
              <el-option v-for="op in options.operators || []" :key="op" :label="op" :value="op" />
            </el-select>
            <el-input v-model="condition.value" placeholder="值" style="width: 78px" />
            <el-select v-model="condition.valueType" style="width: 92px">
              <el-option v-for="t in options.fieldTypes || []" :key="t" :label="t" :value="t" />
            </el-select>
          </div>
          <template v-else>
            <el-input v-model="conditionExpression" type="textarea" :rows="3"
                      placeholder="${amount >= 5000 && (dept == 'SALES' || urgent == true)}" />
            <span class="hint">支持 &amp;&amp; || ! 和括号；字符串用单引号，数字不加引号</span>
          </template>
        </template>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/client'

const props = defineProps<{ modeler: any; processKey?: string }>()
const nodes = ref<any[]>([])
const options = ref<any>({})
const currentId = ref('')
const config = ref<any>(null)
const buttons = ref<any[]>([])
const trigger = ref('')
const condition = reactive<any>({ field: '', operator: '>', value: '', valueType: 'NUMBER' })
const conditionMode = ref<'STRUCTURED' | 'EXPRESSION'>('STRUCTURED')
const conditionExpression = ref('')
const rejectRoute = ref(false)
const store = new Map<string, any>()

// ---------- 多表单 + 二维字段权限 ----------
// baseFormCodes 来自流程发起配置（wf_process_setting.base_form_codes），节点端只读。
// config.formCodes 只存「节点新增」的表单；有效表单 = 基础 ∪ 新增，去重、基础在前。
// fieldPolicyModel 是 el-select 的响应式视图：[formCode][field] = EDITABLE/READONLY/HIDDEN。
// 写回 config.fieldPolicy 时只保留非 EDITABLE 项，保持配置简洁。
const baseFormCodes = ref<string[]>([])
const activeFormCode = ref('')
const formToAdd = ref('')
const syncTargets = ref<string[]>([])
const fieldPolicyModel = reactive<Record<string, Record<string, string>>>({})

function fieldsOf(code: string): any[] {
  const form = (options.value.forms || []).find((f: any) => f.code === code)
  return Array.isArray(form?.fields) ? form.fields : []
}

const effectiveForms = computed<any[]>(() => {
  const codes: string[] = []
  for (const code of [...baseFormCodes.value, ...(config.value?.formCodes || [])]) {
    if (code && !codes.includes(code)) codes.push(code)
  }
  return codes.map((code) => ({
    code,
    name: (options.value.forms || []).find((f: any) => f.code === code)?.name || code,
    base: baseFormCodes.value.includes(code)
  }))
})

const addableForms = computed<any[]>(() => (options.value.forms || [])
  .filter((f: any) => f.code && !effectiveForms.value.some((item) => item.code === f.code)))

const formFields = computed<any[]>(() => fieldsOf(activeFormCode.value))

function initFieldPolicy() {
  for (const key of Object.keys(fieldPolicyModel)) delete fieldPolicyModel[key]
  const policy = config.value?.fieldPolicy || {}
  for (const form of effectiveForms.value) {
    const inner = policy[form.code] || {}
    const model: Record<string, string> = {}
    for (const field of fieldsOf(form.code)) {
      model[field.name] = String(inner[field.name] || 'EDITABLE')
    }
    fieldPolicyModel[form.code] = model
  }
  if (!effectiveForms.value.some((item) => item.code === activeFormCode.value)) {
    activeFormCode.value = effectiveForms.value[0]?.code || ''
  }
}

function syncFieldPolicy() {
  if (!config.value) return
  const result: Record<string, Record<string, string>> = {}
  for (const [code, model] of Object.entries(fieldPolicyModel)) {
    const inner: Record<string, string> = {}
    for (const [field, value] of Object.entries(model)) {
      if (value && value !== 'EDITABLE') inner[field] = value
    }
    if (Object.keys(inner).length) result[code] = inner
  }
  config.value.fieldPolicy = result
}

// options（含表单字段清单）异步到达 / 表单列表变化后，补齐视图，避免模板读到 undefined
watch([effectiveForms, activeFormCode], () => {
  for (const form of effectiveForms.value) {
    if (!fieldPolicyModel[form.code]) fieldPolicyModel[form.code] = {}
    for (const field of fieldsOf(form.code)) {
      if (fieldPolicyModel[form.code][field.name] === undefined) {
        fieldPolicyModel[form.code][field.name] = 'EDITABLE'
      }
    }
  }
}, { immediate: true })

function setAllPolicy(value: string) {
  const code = activeFormCode.value
  if (!code || !fieldPolicyModel[code]) return
  for (const field of formFields.value) fieldPolicyModel[code][field.name] = value
  syncFieldPolicy()
}

function addForm(code: string) {
  if (!code || !config.value) return
  const codes = Array.isArray(config.value.formCodes) ? [...config.value.formCodes] : []
  if (!codes.includes(code) && !baseFormCodes.value.includes(code)) codes.push(code)
  config.value.formCodes = codes
  formToAdd.value = ''
  activeFormCode.value = code
  initFieldPolicy()
  syncFieldPolicy()
}

function removeAddedForm(code: string) {
  if (!config.value) return
  config.value.formCodes = (config.value.formCodes || []).filter((item: string) => item !== code)
  if (activeFormCode.value === code) activeFormCode.value = ''
  initFieldPolicy()
  syncFieldPolicy()
}

async function loadBaseFormCodes() {
  if (!props.processKey) return
  try {
    const { data } = await http.get('/api/workflow/process-settings')
    const setting = (data || []).find((item: any) => item.processKey === props.processKey)
    baseFormCodes.value = Array.isArray(setting?.baseFormCodes) ? [...setting.baseFormCodes] : []
  } catch {
    baseFormCodes.value = []
  }
  initFieldPolicy()
}

// ---------- 批量：复制上一个节点 / 同步到选中节点 ----------
const prevNode = computed(() => {
  const index = nodes.value.findIndex((node) => node.id === currentId.value)
  return index > 0 ? nodes.value[index - 1] : null
})
const otherNodes = computed(() => nodes.value.filter((node) => node.id !== currentId.value))

function copyFromPrevious() {
  const prev = prevNode.value
  if (!prev || !config.value) return
  const source = store.get(prev.id)
  if (!source) {
    ElMessage.warning('上一个节点还没有可复制的配置')
    return
  }
  config.value.formCodes = Array.isArray(source.formCodes) ? [...source.formCodes] : []
  config.value.fieldPolicy = normalizePolicy(source)
  initFieldPolicy()
  persistCurrent()
  ElMessage.success(`已复制「${prev.name || prev.id}」的表单与字段权限`)
}

function syncToNodes() {
  if (!config.value || !syncTargets.value.length) return
  persistCurrent()
  const formCodes = [...(config.value.formCodes || [])]
  const policy = JSON.parse(JSON.stringify(config.value.fieldPolicy || {}))
  let count = 0
  for (const id of syncTargets.value) {
    const node = nodes.value.find((item) => item.id === id)
    const target = store.get(id) || (node ? blank(node) : null)
    if (!target) continue
    target.formCodes = [...formCodes]
    target.fieldPolicy = JSON.parse(JSON.stringify(policy))
    store.set(id, target)
    count += 1
  }
  syncTargets.value = []
  ElMessage.success(`已同步到 ${count} 个节点（只改表单与字段权限）`)
}

function typeLabel(type: string) {
  return type === 'userTask' ? '任务' : type === 'sequenceFlow' ? '连线' : '节点'
}
function isMulti(item: any) {
  return item && (item.signType === 'COUNTERSIGN' || item.signType === 'SEQUENTIAL')
}
function valueHint(type: string) {
  if (type === 'USER') return '用户 ID，多个用逗号分隔'
  if (type === 'ORG_ROLE') return '组织ID:角色编码，如 2:DEPT_MANAGER'
  if (type === 'DEPT_MANAGER') return '主管角色编码，默认 DEPT_MANAGER'
  if (type === 'FORM_FIELD') return '表单字段名，值须为用户 ID'
  return '可留空（发起人 / 上一节点办理人）'
}
function configured(id: string) { return store.has(id) }

function blank(node: any) {
  return {
    nodeId: node.id, nodeName: node.name || node.id, nodeType: node.type,
    assigneeType: node.type === 'userTask' ? 'INITIATOR' : 'INITIATOR',
    assigneeValue: '', signType: 'SINGLE', signRatio: 100,
    formCodes: [], fieldPolicy: {}, decisionConfig: {}, triggerConfig: {},
    conditionConfig: {}, timeoutMinutes: 0
  }
}

// AI 草稿 / 早期前端偶尔还会给单值 formCode 和一维 fieldPolicy。
// 这里只做结构性归一：一维权限按该节点第一个表单装进二维结构，其余非法结构当空，不猜语义。
function normalizePolicy(item: any, formCodes: string[] = []): Record<string, Record<string, string>> {
  const raw = item?.fieldPolicy || {}
  const keys = Object.keys(raw)
  if (!keys.length) return {}
  const flat = keys.every((key) => typeof raw[key] === 'string')
  if (!flat) {
    const result: Record<string, Record<string, string>> = {}
    for (const [code, inner] of Object.entries(raw)) {
      if (code && inner && typeof inner === 'object') {
        result[code] = { ...(inner as Record<string, string>) }
      }
    }
    return result
  }
  const code = formCodes[0] || (typeof item?.formCode === 'string' ? item.formCode : '')
  return code ? { [code]: { ...raw } } : {}
}

function normalizeConfig(item: any) {
  const copy = { ...item }
  const codes = Array.isArray(copy.formCodes)
    ? [...copy.formCodes]
    : (copy.formCode ? [copy.formCode] : [])
  copy.formCodes = codes.filter((code: any) => typeof code === 'string' && code.trim())
  copy.fieldPolicy = normalizePolicy(copy, copy.formCodes)
  delete copy.formCode
  return copy
}

// bpmn-js 的元素类型带命名空间前缀且首字母大写（bpmn:UserTask / bpmn:SequenceFlow），
// 后端编译器认的是 camelCase（userTask / sequenceFlow）。不转就永远匹配不到，
// 面板会一直显示"画布里还没有节点"，像是节点没拖进去。
function normalizeType(type: string) {
  const bare = String(type || '').replace(/^bpmn:/, '')
  return bare ? bare.charAt(0).toLowerCase() + bare.slice(1) : ''
}

function panelEngine() {
  return props.modeler?.value || props.modeler
}

const PANEL_TYPES = ['userTask', 'sequenceFlow', 'exclusiveGateway', 'startEvent', 'endEvent']

function refreshNodes() {
  const engine = panelEngine()
  if (!engine) return
  try {
    const registry = engine.get('elementRegistry')
    nodes.value = registry.filter((item: any) => PANEL_TYPES.includes(normalizeType(item.type)))
      .map((item: any) => ({
        id: item.id,
        name: item.businessObject?.name,
        type: normalizeType(item.type)
      }))
  } catch (error) {
    // 父组件传进来的必须是 bpmn-js 原始实例；如果被 ref() 包成 reactive 代理，
    // 这里会拿不到 elementRegistry。别静默吞掉，否则界面只显示"画布里还没有节点"。
    console.error('[NodeConfigPanel] 读取画布失败', error)
    nodes.value = []
  }
}

// ---------- 画布 ⇄ 面板 双向联动 ----------
// 没有联动时，几十条线里根本分不清列表里那一条对应画布上哪个箭头。

let selectionBound = false

function bindCanvasSelection() {
  const engine = panelEngine()
  if (!engine || selectionBound) return
  try {
    // 画布上点节点/连线 → 面板自动选中对应项。点空白处（newSelection 为空）不动面板，
    // 保留用户正在编辑的配置。
    engine.get('eventBus').on('selection.changed', (event: any) => {
      const element = event.newSelection?.[0]
      if (!element) return
      if (!PANEL_TYPES.includes(normalizeType(element.type))) return
      // currentId 已相等说明这次 changed 是面板自己触发的（select → selection.select → changed），
      // 直接返回切断循环，否则两边会互相触发到栈溢出
      if (currentId.value === element.id) return
      select({ id: element.id, name: element.businessObject?.name, type: normalizeType(element.type) })
    })
    selectionBound = true
  } catch (error) {
    console.error('[NodeConfigPanel] 绑定画布选中事件失败', error)
  }
}

function revealOnCanvas(node: any) {
  const engine = panelEngine()
  if (!engine) return
  try {
    const element = engine.get('elementRegistry').get(node.id)
    if (!element) return
    // 用 bpmn-js 自带的 selection：画布上会出现与"用户亲手点中"一样的选中框，
    // 连线也会被高亮；scrollToElement 把视口滚过去，节点在画布外也能找到
    engine.get('selection').select(element)
    engine.get('canvas').scrollToElement(element)
  } catch (error) {
    console.error('[NodeConfigPanel] 画布定位失败', error)
  }
}

function select(node: any) {
  persistCurrent()
  currentId.value = node.id
  const item = normalizeConfig(store.get(node.id) || blank(node))
  config.value = { ...item }
  formToAdd.value = ''
  syncTargets.value = []
  buttons.value = Array.isArray(item.decisionConfig?.buttons)
    ? item.decisionConfig.buttons.map((b: any) => ({ ...b })) : []
  initFieldPolicy()
  trigger.value = item.triggerConfig?.onEnter || ''
  // 自由表达式优先回填：组合条件无法拆成单个字段，必须原样保留
  conditionExpression.value = item.conditionConfig?.expression || ''
  rejectRoute.value = item.conditionConfig?.rejectRoute === true
  conditionMode.value = conditionExpression.value ? 'EXPRESSION' : 'STRUCTURED'
  Object.assign(condition, { field: '', operator: '>', value: '', valueType: 'NUMBER' },
    item.conditionConfig || {})
  revealOnCanvas(node)
}

// 面板列表跟随 currentId 自动滚动，保证高亮行始终可见
const listRef = ref<HTMLElement | null>(null)
watch(currentId, async () => {
  await nextTick()
  const list = listRef.value
  const active = list?.querySelector('.node-row.active') as HTMLElement | null
  if (!list || !active) return
  const listRect = list.getBoundingClientRect()
  const rect = active.getBoundingClientRect()
  const delta = (rect.top + rect.height / 2) - (listRect.top + listRect.height / 2)
  if (Math.abs(delta) > 4) list.scrollBy({ top: delta, behavior: 'smooth' })
})

function persistCurrent() {
  if (!config.value || !currentId.value) return
  syncFieldPolicy()
  config.value.decisionConfig = { buttons: buttons.value.filter((b) => b.code) }
  config.value.triggerConfig = trigger.value ? { onEnter: trigger.value } : {}
  let conditionConfig: any = {}
  if (conditionMode.value === 'EXPRESSION') {
    if (conditionExpression.value.trim()) conditionConfig = { expression: conditionExpression.value.trim() }
  } else if (condition.field) {
    conditionConfig = {
      field: condition.field, operator: condition.operator,
      value: condition.value, valueType: condition.valueType
    }
  }
  // 驳回路由可以没有条件（=无条件命中），所以标记要独立于条件存在
  if (rejectRoute.value) conditionConfig.rejectRoute = true
  config.value.conditionConfig = conditionConfig
  // AI 草稿里的数值字段可能是 null；统一归一为数字，避免下游按原始类型解析时失败
  if (config.value.nodeType === 'userTask') {
    config.value.signRatio = Number(config.value.signRatio) || 100
    config.value.timeoutMinutes = Number(config.value.timeoutMinutes) || 0
  }
  store.set(currentId.value, { ...config.value })
}

function addButton() {
  buttons.value.push({ code: 'APPROVE', label: '同意', reasonRequired: false, reasonMinLength: 0 })
}
function applyTrigger() { /* 保存时由 persistCurrent 统一写入 */ }

function getConfigs() {
  persistCurrent()
  // 基础表单由流程配置统一继承，节点配置里不再重复带一份，避免两处真相不一致。
  return Array.from(store.values()).map((item) => ({
    ...item,
    formCodes: (item.formCodes || []).filter((code: string) => code && !baseFormCodes.value.includes(code))
  }))
}

function loadConfigs(list: any[]) {
  store.clear()
  for (const item of list || []) {
    const normalized = normalizeConfig(item)
    store.set(normalized.nodeId, normalized)
  }
  if (currentId.value) {
    const node = nodes.value.find((n) => n.id === currentId.value)
    if (node) select(node)
  }
}

defineExpose({ refreshNodes, getConfigs, loadConfigs, reloadBaseForms: loadBaseFormCodes })

// 父组件的 modeler 是在 onMounted 里才 new 出来的，子组件挂载时还是空；
// 只靠父组件主动调 refreshNodes 容易漏（tab 切换、重新导入 XML），这里同步兜一层
watch(() => props.modeler, () => { refreshNodes(); bindCanvasSelection() }, { immediate: true })

// 流程 Key 变更（设计器切流程 / 载入草稿）时基础表单也要跟着换
watch(() => props.processKey, () => { loadBaseFormCodes() })

onMounted(async () => {
  try {
    const { data } = await http.get('/api/workflow/node-configs/options')
    options.value = data
  } catch { options.value = {} }
  await loadBaseFormCodes()
  // options（含表单字段清单）异步到达后，重填一次字段权限视图，
  // 否则用户先点节点再加载完选项时下拉会是空的
  initFieldPolicy()
  refreshNodes()
})
</script>

<style scoped>
.node-panel { border: 1px solid #DCE7E1; border-radius: 12px; background: #FFF; height: 620px; display: flex; flex-direction: column; overflow: hidden; }
.panel-head { padding: 12px 14px; border-bottom: 1px solid #EBEFEC; }
.panel-head .hint { display: block; margin-top: 2px; }
/* 6 行整高（每行 31px）+ 上下 padding 12px = 198px，避免最后一行被裁成半截 */
/* 滚动区底部做 12px 渐隐：节点多于 6 个时最后一行不会被硬裁成半截，看着像「还有更多」 */
.node-list { max-height: 198px; overflow: auto; border-bottom: 1px solid #EBEFEC; padding: 6px;
  -webkit-mask-image: linear-gradient(#000 calc(100% - 12px), transparent);
  mask-image: linear-gradient(#000 calc(100% - 12px), transparent); }
.panel-placeholder { flex: 1; display: grid; place-items: center; padding: 0 18px; color: #8A948F; font-size: 12.5px; text-align: center; line-height: 1.7; }
.node-row { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.node-row:hover { background: #F7FAF8; }
.node-row.active { background: #E1F5EE; }
.node-type { flex: none; font-size: 11px; color: #17564A; background: #E8F4EC; border-radius: 4px; padding: 1px 5px; }
.node-type.sequenceFlow { color: #854F0B; background: #FAEEDA; }
.node-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot-ok { width: 6px; height: 6px; border-radius: 50%; background: #2E7D4F; }
.node-form { flex: 1; overflow: auto; padding: 12px 10px; }
.sub-title { font-size: 13px; font-weight: 600; margin: 12px 0 8px; color: #1C1B18; }
.button-row { display: flex; gap: 4px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
.field-policy-row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.field-label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12.5px; color: #1C1B18; }
.field-empty { padding: 4px 0; }
.field-tip { display: block; margin-top: 2px; font-size: 11.5px; }
.form-bind { width: 100%; }
.form-bind-row { display: flex; align-items: center; gap: 6px; min-height: 24px; margin-bottom: 3px; }
.form-bind-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12.5px; }
.form-tabs { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; max-width: 100%; }
.form-tabs :deep(.el-radio-button__inner) { padding: 5px 9px; font-size: 12px; }
.batch-row { display: flex; gap: 4px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
</style>
