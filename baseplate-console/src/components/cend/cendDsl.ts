import type { Component } from 'vue'
import type { CendAnimation, CendBlock, CendPageDsl } from '../types/cend'

export interface CendBlockDefinition {
  type: string
  label: string
  defaultProps: () => Record<string, any>
  textFields?: Array<{ key: string; label: string }>
  render?: Component
}

export const BLOCK_TYPES = [
  { type: 'hero', label: '头图横幅' },
  { type: 'heading', label: '标题' },
  { type: 'text', label: '正文' },
  { type: 'image', label: '图片' },
  { type: 'button', label: '按钮' },
  { type: 'card', label: '卡片' },
  { type: 'divider', label: '分割线' },
  { type: 'spacer', label: '留白' },
  { type: 'form', label: '表单' },
  { type: 'video', label: '视频' },
  { type: 'carousel', label: '图片轮播' },
  { type: 'nav', label: '导航栏' },
  { type: 'price', label: '价格卡' },
  { type: 'icon', label: '图标' },
  { type: 'countdown', label: '倒计时' },
  { type: 'progress', label: '进度条' },
  { type: 'qrcode', label: '二维码' },
  { type: 'badge', label: '标签' },
  { type: 'rating', label: '评分' },
  { type: 'accordion', label: '折叠面板' },
  { type: 'table', label: '表格' },
  { type: 'notice', label: '公告条' }
] as const

const CUSTOM_BLOCKS: CendBlockDefinition[] = []

export function registerCendBlock(definition: CendBlockDefinition) {
  const index = CUSTOM_BLOCKS.findIndex((item) => item.type === definition.type)
  if (index >= 0) CUSTOM_BLOCKS.splice(index, 1, definition)
  else CUSTOM_BLOCKS.push(definition)
}

export function customCendRenderers(): Record<string, Component> {
  const result: Record<string, Component> = {}
  for (const item of CUSTOM_BLOCKS) {
    if (item.render) result[item.type] = item.render
  }
  return result
}

export function allCendBlockTypes() {
  return [
    ...BLOCK_TYPES.map((item) => ({ type: item.type as string, label: item.label })),
    ...CUSTOM_BLOCKS.map((item) => ({ type: item.type, label: item.label }))
  ]
}

export const ANIMATIONS = ['none', 'fadeIn', 'fadeInUp', 'fadeInDown', 'slideInLeft', 'slideInRight', 'zoomIn', 'bounceIn'] as const

const ALLOWED_TYPES = new Set<string>(BLOCK_TYPES.map((item) => item.type))
function isAllowedType(type: unknown): type is CendBlock['type'] {
  return typeof type === 'string' && (ALLOWED_TYPES.has(type) || CUSTOM_BLOCKS.some((item) => item.type === type))
}
const ALLOWED_STYLE_KEYS = new Set([
  'color', 'background', 'backgroundColor', 'backgroundImage', 'fontSize', 'fontWeight',
  'textAlign', 'padding', 'margin', 'width', 'height', 'minHeight', 'borderRadius',
  'boxShadow', 'border', 'opacity', 'letterSpacing', 'lineHeight', 'gap'
])

let seq = 0
export function newId(prefix = 'b') {
  seq += 1
  return `${prefix}_${Date.now().toString(36)}_${seq}`
}

export function emptyDsl(): CendPageDsl {
  return {
    canvas: { width: 375, mode: 'mobile' },
    theme: { primary: '#10b981', text: '#1f2937', background: '#ffffff', radius: 12 },
    blocks: []
  }
}

export function defaultProps(type: CendBlock['type']): Record<string, any> {
  switch (type) {
    case 'hero': return { title: '欢迎光临', subtitle: '在这里写下吸引人的副标题', image: '' }
    case 'heading': return { text: '标题文字', level: 2 }
    case 'text': return { text: '在这里写下正文内容。' }
    case 'image': return { src: '', alt: '图片' }
    case 'button': return { text: '立即报名', url: '', variant: 'primary' }
    case 'card': return { title: '卡片标题', text: '卡片内容', children: [] }
    case 'divider': return {}
    case 'spacer': return { height: 24 }
    case 'form': return { formCode: '', title: '报名信息', fields: [] }
    case 'video': return { src: '', poster: '', autoplay: false }
    case 'carousel': return { images: [], interval: 3000 }
    case 'nav': return { items: [{ label: '首页', url: '' }, { label: '关于', url: '' }] }
    case 'price': return { title: '标准版', price: '99', originalPrice: '199', unit: '元/月', buttonText: '立即购买' }
    case 'icon': return { icon: '★', size: 48, color: '#f59e0b' }
    case 'countdown': return { label: '距活动结束', deadline: '' }
    case 'progress': return { label: '完成进度', value: 60 }
    case 'qrcode': return { src: '', caption: '扫码关注' }
    case 'badge': return { text: '热门', tone: 'primary' }
    case 'rating': return { value: 5, count: 128 }
    case 'accordion': return { items: [{ title: '常见问题', text: '这里填写答案' }] }
    case 'table': return { headers: ['项目', '说明'], rows: [['示例', '内容']] }
    case 'notice': return { text: '这是一条公告', tone: 'info' }
  }
}

export function defaultPropsFor(type: string): Record<string, any> {
  const custom = CUSTOM_BLOCKS.find((item) => item.type === type)
  if (custom) return custom.defaultProps()
  return defaultProps(type as CendBlock['type'])
}

function safeText(value: unknown, fallback = '') {
  return value === undefined || value === null ? fallback : String(value)
}

function safeMedia(value: unknown) {
  const text = safeText(value)
  if (/^(https?:\/\/|data:image\/|\/assets\/)/i.test(text)) return text
  return ''
}

function safeStyle(raw: unknown): Record<string, any> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const result: Record<string, any> = {}
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (!ALLOWED_STYLE_KEYS.has(key)) continue
    if (typeof value === 'number' || typeof value === 'string') {
      const text = String(value)
      if (/javascript:|expression\(|<script/i.test(text)) continue
      result[key] = value
    }
  }
  return result
}

function safeAnimation(raw: unknown): CendAnimation {
  if (!raw || typeof raw !== 'object') return {}
  const item = raw as Record<string, unknown>
  const name = safeText(item.name, 'none')
  return {
    name: (ANIMATIONS as readonly string[]).includes(name) ? name : 'none',
    duration: Math.min(5000, Math.max(0, Number(item.duration) || 600)),
    delay: Math.min(5000, Math.max(0, Number(item.delay) || 0))
  }
}

function normalizeBlock(raw: unknown, depth = 0): CendBlock | null {
  if (!raw || typeof raw !== 'object' || depth > 4) return null
  const item = raw as Record<string, any>
  const type = isAllowedType(item.type) ? item.type : 'text'
  const props = item.props && typeof item.props === 'object' ? item.props : item
  const children = Array.isArray(item.children)
    ? item.children.map((child: unknown) => normalizeBlock(child, depth + 1)).filter(Boolean) as CendBlock[]
    : []
  return {
    id: safeText(item.id) || newId(),
    type,
    props: {
      ...defaultPropsFor(type),
      ...sanitizeProps(type, props),
      ...(type === 'card' || type === 'hero' ? { children } : {})
    },
    style: safeStyle(item.style),
    animation: safeAnimation(item.animation)
  }
}

function sanitizeProps(type: CendBlock['type'], raw: Record<string, any>): Record<string, any> {
  const result: Record<string, any> = {}
  for (const key of ['title', 'subtitle', 'text', 'alt', 'formCode', 'variant', 'price', 'originalPrice',
    'unit', 'buttonText', 'icon', 'color', 'caption', 'tone', 'label', 'submitText']) {
    if (raw[key] !== undefined) result[key] = safeText(raw[key])
  }
  if (raw.level !== undefined) result.level = Math.min(4, Math.max(1, Number(raw.level) || 2))
  if (raw.height !== undefined) result.height = Math.min(400, Math.max(0, Number(raw.height) || 24))
  if (raw.size !== undefined) result.size = Math.min(200, Math.max(8, Number(raw.size) || 48))
  if (raw.value !== undefined) result.value = Math.min(100, Math.max(0, Number(raw.value) || 0))
  if (raw.count !== undefined) result.count = Math.min(99999, Math.max(0, Number(raw.count) || 0))
  if (raw.interval !== undefined) result.interval = Math.min(10000, Math.max(500, Number(raw.interval) || 3000))
  if (raw.autoplay !== undefined) result.autoplay = Boolean(raw.autoplay)
  if (raw.src !== undefined) result.src = safeMedia(raw.src)
  if (raw.image !== undefined) result.image = safeMedia(raw.image)
  if (raw.poster !== undefined) result.poster = safeMedia(raw.poster)
  if (raw.url !== undefined) result.url = safeMedia(raw.url)
  if (raw.deadline !== undefined) result.deadline = safeText(raw.deadline)
  if (Array.isArray(raw.images)) result.images = raw.images.slice(0, 12).map(safeMedia).filter(Boolean)
  if (Array.isArray(raw.items)) result.items = raw.items.slice(0, 12).map((item: any) => ({
    title: safeText(item?.title ?? item?.label),
    text: safeText(item?.text),
    label: safeText(item?.label ?? item?.title),
    url: safeMedia(item?.url)
  }))
  if (Array.isArray(raw.headers)) result.headers = raw.headers.slice(0, 8).map((h: any) => safeText(h))
  if (Array.isArray(raw.rows)) result.rows = raw.rows.slice(0, 20).map((row: any) =>
    Array.isArray(row) ? row.slice(0, 8).map((cell: any) => safeText(cell)) : [])
  if (Array.isArray(raw.fields)) {
    result.fields = raw.fields.slice(0, 30).map((field: any) => ({
      name: safeText(field?.name),
      label: safeText(field?.label, safeText(field?.name)),
      type: ['input', 'textarea', 'number', 'date', 'select'].includes(safeText(field?.type)) ? safeText(field.type) : 'input',
      required: Boolean(field?.required),
      placeholder: safeText(field?.placeholder),
      options: Array.isArray(field?.options) ? field.options.slice(0, 20).map((o: any) => safeText(o?.label ?? o)) : []
    })).filter((field: any) => field.name)
  }
  return result
}

function parseMaybeJson(value: unknown): any {
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) } catch { return value }
}

export function normalizeAiPage(args: Record<string, any>): CendPageDsl {
  const canvasRaw = parseMaybeJson(args.canvas)
  const themeRaw = parseMaybeJson(args.theme)
  const blocksRaw = parseMaybeJson(args.blocks)
  const canvas = canvasRaw && typeof canvasRaw === 'object' ? canvasRaw : {}
  const theme = themeRaw && typeof themeRaw === 'object' ? themeRaw : {}
  const blocks = Array.isArray(blocksRaw) ? blocksRaw : []
  const dsl = emptyDsl()
  return {
    canvas: {
      width: Math.min(1200, Math.max(280, Number(canvas.width) || 375)),
      mode: canvas.mode === 'desktop' ? 'desktop' : 'mobile'
    },
    theme: {
      primary: safeText(theme.primary, dsl.theme?.primary),
      text: safeText(theme.text, dsl.theme?.text),
      background: safeText(theme.background, dsl.theme?.background),
      radius: Math.min(48, Math.max(0, Number(theme.radius) || 12))
    },
    blocks: blocks.map((block: unknown) => normalizeBlock(block)).filter(Boolean) as CendBlock[]
  }
}

export function schemaOf(dsl: CendPageDsl) {
  const fields: Array<Record<string, any>> = []
  const visit = (blocks: CendBlock[]) => {
    for (const block of blocks) {
      if (block.type === 'form' && Array.isArray(block.props?.fields)) {
        for (const field of block.props.fields) {
          fields.push({
            name: field.name,
            title: field.label || field.name,
            type: 'STRING'
          })
        }
      }
      if (Array.isArray(block.children)) visit(block.children)
      if (Array.isArray(block.props?.children)) visit(block.props.children)
    }
  }
  visit(dsl.blocks)
  return { fields }
}
