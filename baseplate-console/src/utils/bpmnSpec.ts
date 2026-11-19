/**
 * 把 bpmn-js 画布实例转成和草稿 spec 同构的 nodes / edges。
 *
 * <p>AI 读取当前画布时，模型只需要「节点 + 连线 + 条件」这些结构信息；
 * 完整 BPMN XML 留给前端卡片渲染，避免把 DI 坐标塞进模型上下文。</p>
 */
const NODE_TYPES: Record<string, string> = {
  'bpmn:StartEvent': 'START',
  'bpmn:UserTask': 'APPROVAL',
  'bpmn:ExclusiveGateway': 'EXCLUSIVE_GATEWAY',
  'bpmn:EndEvent': 'END'
}

export interface BpmnSpecSummary {
  processKey: string
  name: string
  nodes: Array<Record<string, any>>
  edges: Array<Record<string, any>>
}

export function bpmnToSpec(modeler: any, fallbackKey: string): BpmnSpecSummary {
  const registry = modeler.get('elementRegistry')
  // diagram-js 的 Canvas 只提供 getRootElement()；早期写法 getRoot() 在当前版本不存在，
  // 直接调用会抛 "getRoot is not a function"，导致 AI 的「读取画布」工具整体失败。
  const root = modeler.get('canvas').getRootElement()
  const process = root?.businessObject || {}

  const nodes: Array<Record<string, any>> = []
  const nodeIds = new Set<string>()
  for (const element of registry.getAll()) {
    // 文本标签不是业务节点，跟随所属元素渲染即可
    if (element.labelTarget) continue
    const type = NODE_TYPES[element.type]
    if (!type) continue
    nodeIds.add(element.id)
    const node: Record<string, any> = {
      id: element.id,
      name: element.businessObject?.name || element.id,
      type
    }
    const assignee = element.businessObject?.assignee
    if (assignee) node.assignee = assignee
    nodes.push(node)
  }

  const edges: Array<Record<string, any>> = []
  for (const element of registry.getAll()) {
    if (element.type !== 'bpmn:SequenceFlow') continue
    const from = element.source?.id
    const to = element.target?.id
    // 只保留两端都是受支持节点的连线，边界事件等暂不进入 AI 的 spec
    if (!from || !to || !nodeIds.has(from) || !nodeIds.has(to)) continue
    const bo = element.businessObject || {}
    const edge: Record<string, any> = { from, to }
    if (bo.name) edge.name = bo.name
    const condition = bo.conditionExpression?.body
    if (condition) edge.condition = condition
    if (element.source?.businessObject?.default?.id === bo.id) edge.isDefault = true
    edges.push(edge)
  }

  return {
    processKey: process.id || fallbackKey,
    name: process.name || '',
    nodes,
    edges
  }
}
