<template>
  <form-create v-if="rules.length" v-model="model" :rule="rules" :option="formOption"
               v-model:api="api" />
  <el-empty v-else description="该表单还没有字段，请先在「流程表单」里拖几个控件" :image-size="60" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 通用动态表单渲染器：任何页面给一段 form-create 规则就能用，
 * 组件素材库里保存的片段也通过它复用。
 * 兼容两种存储格式：纯 rule 数组，或 { rule, option, actions } 包装对象。
 */
const props = defineProps<{
  rule?: any[] | string
  option?: any
  modelValue?: Record<string, any>
  readonly?: boolean
}>()
const emit = defineEmits(['update:modelValue'])

const model = ref<Record<string, any>>({ ...(props.modelValue || {}) })
const api = ref<any>(null)

watch(() => props.modelValue, (value) => {
  if (value) model.value = { ...value }
})
watch(model, (value) => emit('update:modelValue', value), { deep: true })

const parsed = computed(() => {
  let value: any = props.rule
  if (typeof value === 'string') {
    try { value = JSON.parse(value) } catch { return null }
  }
  return value
})

const rules = computed<any[]>(() => {
  const value: any = parsed.value
  const list = Array.isArray(value) ? value : value?.rule
  if (!Array.isArray(list)) return []
  if (!props.readonly) return list
  return list.map((item: any) => ({ ...item, props: { ...(item.props || {}), disabled: true } }))
})

const actions = computed<any[]>(() => {
  const value: any = parsed.value
  return Array.isArray(value?.actions) ? value.actions : []
})

const formOption = computed(() => ({
  submitBtn: false,
  resetBtn: false,
  ...(Array.isArray(parsed.value) ? (props.option || {}) : (parsed.value?.option || props.option || {}))
}))

// 受控联动动作：字段 A 满足条件时显示/隐藏字段 B。事件只允许这种结构化配置，
// 不把任意 JS 交到页面上执行。
watch(model, (value) => {
  if (!api.value || !actions.value.length) return
  for (const action of actions.value) {
    const source = action?.when?.field
    const target = action?.then?.field
    if (!source || !target || !api.value.hidden) continue
    const left = Number(value[source])
    const right = Number(action.when.value)
    const operator = action.when.operator || '=='
    let hit = false
    if (!Number.isNaN(left) && !Number.isNaN(right)) {
      hit = operator === '>' ? left > right : operator === '>=' ? left >= right
        : operator === '<' ? left < right : operator === '<=' ? left <= right
        : operator === '!=' ? left !== right : left === right
    } else {
      const a = String(value[source] ?? '')
      const b = String(action.when.value ?? '')
      hit = operator === '!=' ? a !== b : a === b
    }
    const show = action.then.type === 'HIDE' ? !hit : hit
    api.value.hidden(!show, target)
  }
}, { deep: true })

defineExpose({
  api,
  validate: () => api.value?.validate?.(),
  formData: () => api.value?.formData?.() || model.value
})
</script>
