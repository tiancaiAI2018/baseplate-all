/**
 * 参数 Schema 的可视化模型与转换。
 *
 * <p>JSON Schema 本身是递归结构，可视化编辑器必须保留层级：
 * object 有子字段，array 有元素类型（元素也可以是 object）。</p>
 */

export interface SchemaNode {
  name: string
  type: string
  /** 仅 array 使用：元素类型 */
  itemType: string
  required: boolean
  description: string
  /** object 的子字段；array 且元素为 object 时表示元素字段 */
  children: SchemaNode[]
}

export const SCHEMA_TYPES = ['string', 'number', 'integer', 'boolean', 'array', 'object']

export function emptyNode(): SchemaNode {
  return {
    name: '',
    type: 'string',
    itemType: 'string',
    required: false,
    description: '',
    children: []
  }
}

/** JSON Schema → 树：object 递归 properties，array 递归 items.properties。 */
export function schemaToNodes(schema: any): SchemaNode[] {
  const properties = schema?.properties
  if (!properties || typeof properties !== 'object') {
    return []
  }
  const required: string[] = Array.isArray(schema?.required) ? schema.required : []
  return Object.entries(properties).map(([name, definition]: [string, any]) => {
    const type = SCHEMA_TYPES.includes(String(definition?.type))
      ? String(definition.type) : 'string'
    const itemType = type === 'array'
      ? String(definition?.items?.type || 'string') : 'string'
    const children = type === 'object'
      ? schemaToNodes(definition)
      : (type === 'array' && itemType === 'object' ? schemaToNodes(definition.items) : [])
    return {
      name,
      type,
      itemType: SCHEMA_TYPES.includes(itemType) ? itemType : 'string',
      required: required.includes(name),
      description: String(definition?.description || ''),
      children
    }
  })
}

/** 树 → JSON Schema：空名字的节点直接跳过。 */
export function nodesToSchema(nodes: SchemaNode[]): Record<string, any> {
  const properties: Record<string, any> = {}
  const required: string[] = []
  for (const node of nodes) {
    const name = node.name.trim()
    if (!name) {
      continue
    }
    const definition: Record<string, any> = { type: node.type || 'string' }
    if (node.description.trim()) {
      definition.description = node.description.trim()
    }
    if (node.type === 'object') {
      const nested = nodesToSchema(node.children)
      definition.properties = nested.properties
      if (Array.isArray(nested.required) && nested.required.length) {
        definition.required = nested.required
      }
    } else if (node.type === 'array') {
      if (node.itemType === 'object') {
        const nested = nodesToSchema(node.children)
        definition.items = { type: 'object', properties: nested.properties }
        if (Array.isArray(nested.required) && nested.required.length) {
          definition.items.required = nested.required
        }
      } else {
        definition.items = { type: node.itemType || 'string' }
      }
    }
    properties[name] = definition
    if (node.required) {
      required.push(name)
    }
  }
  const schema: Record<string, any> = { type: 'object', properties }
  if (required.length) {
    schema.required = required
  }
  return schema
}

/** 判断 schema 是否还能用可视化表达；元组、oneOf 等结构退回 JSON 模式。 */
export function schemaFitsVisual(schema: any): boolean {
  const fits = (definition: any): boolean => {
    if (!definition || typeof definition !== 'object') {
      return false
    }
    if (definition.oneOf || definition.anyOf || definition.allOf) {
      return false
    }
    if (definition.type === 'object') {
      if (!definition.properties || typeof definition.properties !== 'object') {
        return true
      }
      return Object.values(definition.properties).every(fits)
    }
    if (definition.type === 'array') {
      if (!definition.items) {
        return true
      }
      if (Array.isArray(definition.items)) {
        return false
      }
      return definition.items.type === 'object'
        ? fits(definition.items)
        : typeof definition.items === 'object'
    }
    return true
  }
  if (!schema?.properties || typeof schema.properties !== 'object') {
    return true
  }
  return Object.values(schema.properties).every(fits)
}
