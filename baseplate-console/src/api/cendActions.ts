import { getCendDesigner } from './cendDesigner'

const ALLOWED_TYPES = new Set(['text', 'button', 'img', 'container', 'page', 'phone-input', 'share-button'])
const ALLOWED_STYLE_KEYS = new Set([
  'position', 'left', 'right', 'top', 'bottom', 'width', 'height', 'minWidth', 'minHeight', 'maxWidth',
  'display', 'flexDirection', 'justifyContent', 'alignItems', 'flexWrap', 'gap',
  'padding', 'margin', 'boxSizing', 'background', 'backgroundColor', 'color', 'fontSize', 'fontWeight',
  'fontFamily', 'lineHeight', 'letterSpacing', 'textAlign', 'textDecoration', 'whiteSpace',
  'border', 'borderRadius', 'boxShadow', 'opacity', 'overflow', 'overflowX', 'overflowY',
  'transform', 'animation', 'backdropFilter', 'zIndex'
])
const ALLOWED_ACTIONS = new Set(['goPage', 'setVariable', 'callApi', 'submit', 'toast', 'copyLink'])

type Node = Record<string, any>
type Dsl = { items: Node[] }

function text(value: unknown, fallback = '') {
  return value === undefined || value === null ? fallback : String(value)
}

function safeStyle(raw: unknown): Record<string, any> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const result: Record<string, any> = {}
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (!ALLOWED_STYLE_KEYS.has(key)) continue
    if (typeof value === 'number' || typeof value === 'string') {
      const str = String(value)
      if (/javascript:|expression\(|<script/i.test(str)) continue
      result[key] = str
    }
  }
  return result
}

function id() {
  return `n_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`
}

function findNode(items: Node[], target: string): { node: Node; parent: Node[]; index: number } | null {
  for (let index = 0; index < items.length; index += 1) {
    const node = items[index]
    if (node.id === target) return { node, parent: items, index }
    if (Array.isArray(node.items)) {
      const found = findNode(node.items, target)
      if (found) return found
    }
  }
  return null
}

function makeNode(args: any): Node {
  const type = ALLOWED_TYPES.has(args.type) ? args.type : 'text'
  const props = args.props && typeof args.props === 'object' ? args.props : {}
  const node: Node = {
    id: text(args.id) || id(),
    type,
    name: text(args.name, type),
    style: safeStyle(args.style)
  }
  if (type === 'text') node.text = text(props.text ?? args.text, '请输入文本')
  if (type === 'button' || type === 'share-button') node.text = text(props.text ?? args.text, '按钮')
  if (type === 'phone-input') {
    node.placeholder = text(props.placeholder, '请输入手机号')
    node.prefix = text(props.prefix, '')
  }
  if (type === 'img' || type === 'page') node.src = text(props.src, '')
  if (Array.isArray(args.children)) node.items = args.children.map(makeNode)
  return node
}

async function finish(result: Record<string, any>, includeScreenshot?: boolean) {
  if (includeScreenshot !== true) return { ok: true, result }
  const screenshot = await getCendDesigner()?.screenshot().catch(() => '')
  return {
    ok: true,
    result: { ...result, screenshot: screenshot || '(截图失败)' }
  }
}

function requireDesigner() {
  const bridge = getCendDesigner()
  if (!bridge) return { error: '当前没有打开 C 端页面设计器' }
  const dsl = bridge.getDsl() as Dsl
  if (!dsl?.items?.length) return { error: '页面 DSL 为空' }
  return { bridge, dsl }
}

export async function cendGetTree(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const lines: string[] = []
  const walk = (items: Node[], depth: number) => {
    for (const node of items) {
      lines.push(`${'  '.repeat(depth)}${node.id} [${node.type}] ${node.name || ''} ${node.text || ''}`)
      if (Array.isArray(node.items)) walk(node.items, depth + 1)
    }
  }
  walk(ctx.dsl!.items, 0)
  return finish({ code: ctx.bridge!.code(), tree: lines.join('\n'), total: lines.length }, args.includeScreenshot)
}

export async function cendAddComponent(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const node = makeNode(args)
  const dsl = ctx.dsl!
  const parent = args.parentId ? findNode(dsl.items, String(args.parentId))?.node : dsl.items[0]
  if (!parent) return { ok: false, error: `找不到父容器：${args.parentId}` }
  if (!Array.isArray(parent.items)) parent.items = []
  const items = parent.items as Node[]
  const index = Number.isInteger(args.index) ? Math.max(0, Math.min(args.index, items.length)) : items.length
  items.splice(index, 0, node)
  ctx.bridge!.setDsl({ ...dsl, items: [...dsl.items] })
  return finish({ id: node.id, type: node.type, parentId: parent.id || 'page', message: '组件已添加到画布' }, args.includeScreenshot)
}

export async function cendUpdateComponent(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const found = findNode(ctx.dsl!.items, String(args.id || ''))
  if (!found) return { ok: false, error: `找不到组件：${args.id}` }
  if (args.name !== undefined) found.node.name = text(args.name, found.node.name)
  if (args.text !== undefined) found.node.text = text(args.text)
  if (args.props && typeof args.props === 'object' && !Array.isArray(args.props)) {
    found.node = { ...found.node, ...args.props }
  }
  if (args.style && typeof args.style === 'object' && !Array.isArray(args.style)) {
    found.node.style = { ...(found.node.style || {}), ...safeStyle(args.style) }
  }
  found.parent[found.index] = found.node
  ctx.bridge!.setDsl({ ...ctx.dsl!, items: [...ctx.dsl!.items] })
  return finish({ id: found.node.id, message: '组件已更新' }, args.includeScreenshot)
}

export async function cendRemoveComponent(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const found = findNode(ctx.dsl!.items, String(args.id || ''))
  if (!found) return { ok: false, error: `找不到组件：${args.id}` }
  if (found.node.id === ctx.dsl!.items[0].id) return { ok: false, error: '不能删除页面根节点' }
  found.parent.splice(found.index, 1)
  ctx.bridge!.setDsl({ ...ctx.dsl!, items: [...ctx.dsl!.items] })
  return finish({ id: args.id, message: '组件已删除' }, args.includeScreenshot)
}

export async function cendMoveComponent(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const dsl = ctx.dsl!
  const found = findNode(dsl.items, String(args.id || ''))
  if (!found) return { ok: false, error: `找不到组件：${args.id}` }
  const target = args.parentId ? findNode(dsl.items, String(args.parentId))?.node : dsl.items[0]
  if (!target) return { ok: false, error: `找不到目标容器：${args.parentId}` }
  if (!Array.isArray(target.items)) target.items = []
  found.parent.splice(found.index, 1)
  const items = target.items as Node[]
  const index = Number.isInteger(args.index) ? Math.max(0, Math.min(args.index, items.length)) : items.length
  items.splice(index, 0, found.node)
  ctx.bridge!.setDsl({ ...dsl, items: [...dsl.items] })
  return finish({ id: args.id, parentId: target.id || 'page', message: '组件已移动' }, args.includeScreenshot)
}

export async function cendBindEvent(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const found = findNode(ctx.dsl!.items, String(args.id || ''))
  if (!found) return { ok: false, error: `找不到组件：${args.id}` }
  const eventName = text(args.event, 'click')
  const rawActions = Array.isArray(args.actions) ? args.actions : []
  const actions = rawActions
    .map((item: any) => {
      const action = text(item?.action, '')
      if (!ALLOWED_ACTIONS.has(action)) return null
      const clean: Record<string, any> = { action }
      for (const key of ['pageId', 'name', 'value', 'url', 'message']) {
        if (item?.[key] !== undefined) clean[key] = text(item[key])
      }
      return clean
    })
    .filter(Boolean)
  found.node.events = { ...(found.node.events || {}), [eventName]: actions }
  found.parent[found.index] = found.node
  ctx.bridge!.setDsl({ ...ctx.dsl!, items: [...ctx.dsl!.items] })
  return finish({ id: args.id, event: eventName, actions, message: '事件已绑定' }, args.includeScreenshot)
}

export async function cendScreenshot(args: any) {
  const ctx = requireDesigner()
  if (ctx.error) return { ok: false, error: ctx.error }
  const screenshot = await ctx.bridge!.screenshot().catch(() => '')
  if (!screenshot) return { ok: false, error: '截图失败，请确认设计器画布已打开' }
  return finish({ code: ctx.bridge!.code(), screenshot }, false)
}
