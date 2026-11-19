/**
 * 把 AI 下发的字段清单编译成设计器能直接打开的产物：
 * 1. form-create 规则（写进 uiSchema.rule，决定表单长什么样）
 * 2. 元数据字段（写进 schema.fields，决定数据是什么类型）
 *
 * 两套词表并不相同：设计器用 inputNumber/datePicker，元数据只认 NUMBER/DATE。
 * 集中在这里做映射，避免设计器与后端各维护一份导致字段类型对不上。
 */

export interface AiFormField {
  field?: string
  title?: string
  type?: string
  required?: boolean
  placeholder?: string
  defaultValue?: unknown
  options?: Array<{ label?: string; value?: unknown } | string>
}

export interface FormArtifacts {
  rules: Record<string, any>[]
  schemaFields: Array<{ name: string; title: string; type: string }>
}

/** AI 类型 → form-create 组件名；未知类型统一退化为单行文本，保证能渲染。 */
const COMPONENT_TYPES: Record<string, string> = {
  input: 'input',
  textarea: 'textarea',
  number: 'inputNumber',
  slider: 'slider',
  date: 'datePicker',
  datetime: 'timePicker',
  switch: 'switch',
  select: 'select',
  radio: 'radio',
  checkbox: 'checkbox',
  upload: 'upload',
  editor: 'editor'
}

/** form-create 组件名 → 元数据白名单类型（与 MetadataService.FIELD_TYPES 对齐）。 */
const METADATA_TYPES: Record<string, string> = {
  input: 'STRING',
  textarea: 'TEXT',
  inputNumber: 'NUMBER',
  slider: 'NUMBER',
  datePicker: 'DATE',
  timePicker: 'DATETIME',
  switch: 'BOOLEAN',
  select: 'SELECT',
  radio: 'SELECT',
  checkbox: 'MULTI_SELECT',
  upload: 'FILE',
  editor: 'RICH_TEXT'
}

const CHOICE_COMPONENTS = new Set(['select', 'radio', 'checkbox'])

/** 表单编码/字段名必须是后端能接受的形态：小写字母开头，避免生成存不进去的定义。 */
export const FIELD_NAME_PATTERN = /^[a-z][a-z0-9_]*$/

/** 模型有时把数组序列化成字符串下发，这里统一还原成数组。 */
export function normalizeAiFields(raw: unknown): AiFormField[] {
  const list = typeof raw === 'string' ? safeParse(raw) : raw
  return Array.isArray(list) ? (list as AiFormField[]) : []
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return []
  }
}

export function buildFormArtifacts(fields: AiFormField[]): FormArtifacts {
  return {
    rules: fields.map(toRule),
    schemaFields: fields.map(toSchemaField)
  }
}

function toRule(field: AiFormField): Record<string, any> {
  const component = COMPONENT_TYPES[String(field.type || 'input')] || 'input'
  const title = field.title || field.field || ''
  const rule: Record<string, any> = {
    type: component,
    field: field.field,
    title
  }
  if (field.placeholder) {
    rule.props = { placeholder: field.placeholder }
  }
  if (field.defaultValue !== undefined && field.defaultValue !== null && field.defaultValue !== '') {
    rule.value = field.defaultValue
  }
  const options = normalizeOptions(field.options)
  if (CHOICE_COMPONENTS.has(component) && options.length) {
    rule.options = options
  }
  if (field.required) {
    // form-create 的必填校验挂在 validate 上，设计器打开后用户仍可在属性面板里改
    rule.validate = [{ required: true, message: `请填写${title}`, trigger: ['blur', 'change'] }]
  }
  return rule
}

function toSchemaField(field: AiFormField) {
  const component = COMPONENT_TYPES[String(field.type || 'input')] || 'input'
  return {
    name: String(field.field || ''),
    title: field.title || String(field.field || ''),
    type: METADATA_TYPES[component] || 'STRING'
  }
}

/** 选项既接受字符串数组，也接受 { label, value } 对象数组。 */
function normalizeOptions(options: AiFormField['options']) {
  if (!Array.isArray(options)) return []
  return options
    .map((option) => {
      if (typeof option === 'string') {
        return { label: option, value: option }
      }
      const value = option?.value === undefined ? option?.label : option.value
      return { label: String(option?.label ?? value ?? ''), value: String(value ?? '') }
    })
    .filter((option) => option.label !== '')
}
