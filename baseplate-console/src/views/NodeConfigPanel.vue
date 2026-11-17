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
          <el-select v-model="config.formCode" clearable style="width: 100%">
            <el-option v-for="form in options.forms || []" :key="form.code"
                       :label="`${form.name || form.code}（v${form.version}）`" :value="form.code" />
          </el-select>
        </el-form-item>
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
          <div class="sub-title">出线条件（结构化，不写表达式）</div>
          <div class="button-row">
            <el-input v-model="condition.field" placeholder="字段" style="width: 92px" />
            <el-select v-model="condition.operator" style="width: 78px">
              <el-option v-for="op in options.operators || []" :key="op" :label="op" :value="op" />
            </el-select>
            <el-input v-model="condition.value" placeholder="值" style="width: 78px" />
            <el-select v-model="condition.valueType" style="width: 92px">
              <el-option v-for="t in options.fieldTypes || []" :key="t" :label="t" :value="t" />
            </el-select>
          </div>
        </template>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import http from '../api/client'

const props = defineProps<{ modeler: any }>()
const nodes = ref<any[]>([])
const options = ref<any>({})
const currentId = ref('')
const config = ref<any>(null)
const buttons = ref<any[]>([])
const trigger = ref('')
const condition = reactive<any>({ field: '', operator: '>', value: '', valueType: 'NUMBER' })
const store = new Map<string, any>()

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
    formCode: '', fieldPolicy: {}, decisionConfig: {}, triggerConfig: {},
    conditionConfig: {}, timeoutMinutes: 0
  }
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
  const item = store.get(node.id) || blank(node)
  config.value = { ...item }
  buttons.value = Array.isArray(item.decisionConfig?.buttons)
    ? item.decisionConfig.buttons.map((b: any) => ({ ...b })) : []
  trigger.value = item.triggerConfig?.onEnter || ''
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
  config.value.decisionConfig = { buttons: buttons.value.filter((b) => b.code) }
  config.value.triggerConfig = trigger.value ? { onEnter: trigger.value } : {}
  config.value.conditionConfig = condition.field
    ? { field: condition.field, operator: condition.operator, value: condition.value, valueType: condition.valueType }
    : {}
  store.set(currentId.value, { ...config.value })
}

function addButton() {
  buttons.value.push({ code: 'APPROVE', label: '同意', reasonRequired: false, reasonMinLength: 0 })
}
function applyTrigger() { /* 保存时由 persistCurrent 统一写入 */ }

function getConfigs() {
  persistCurrent()
  return Array.from(store.values())
}

function loadConfigs(list: any[]) {
  store.clear()
  for (const item of list || []) {
    store.set(item.nodeId, { ...item })
  }
  if (currentId.value) {
    const node = nodes.value.find((n) => n.id === currentId.value)
    if (node) select(node)
  }
}

defineExpose({ refreshNodes, getConfigs, loadConfigs })

// 父组件的 modeler 是在 onMounted 里才 new 出来的，子组件挂载时还是空；
// 只靠父组件主动调 refreshNodes 容易漏（tab 切换、重新导入 XML），这里同步兜一层
watch(() => props.modeler, () => { refreshNodes(); bindCanvasSelection() }, { immediate: true })

onMounted(async () => {
  try {
    const { data } = await http.get('/api/workflow/node-configs/options')
    options.value = data
  } catch { options.value = {} }
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
</style>
