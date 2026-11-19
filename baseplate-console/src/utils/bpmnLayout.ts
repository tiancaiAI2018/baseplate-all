/**
 * BPMN 自动排版：只重算图形信息（DI），不改节点、属性与条件。
 *
 * <p>用 dagre 做从左到右的分层布局；回退边（目标层级不高于源）不参与分层，
 * 单独从节点下方绕行，否则会把整张图拉成一列。</p>
 */
import dagre from '@dagrejs/dagre'

const BPMN_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
const BPMNDI_NS = 'http://www.omg.org/spec/BPMN/20100524/DI'
const DC_NS = 'http://www.omg.org/spec/DD/20100524/DC'
const DI_NS = 'http://www.omg.org/spec/DD/20100524/DI'

const NODE_TYPES = new Set([
  'startEvent', 'endEvent', 'userTask', 'serviceTask', 'scriptTask',
  'manualTask', 'receiveTask', 'sendTask', 'businessRuleTask',
  'exclusiveGateway', 'parallelGateway', 'inclusiveGateway', 'eventBasedGateway',
  'subProcess', 'callActivity', 'intermediateCatchEvent', 'intermediateThrowEvent',
  'boundaryEvent'
])

const DEFAULT_SIZE: Record<string, { width: number; height: number }> = {
  startEvent: { width: 36, height: 36 },
  endEvent: { width: 36, height: 36 },
  intermediateCatchEvent: { width: 36, height: 36 },
  intermediateThrowEvent: { width: 36, height: 36 },
  exclusiveGateway: { width: 50, height: 50 },
  parallelGateway: { width: 50, height: 50 },
  inclusiveGateway: { width: 50, height: 50 },
  eventBasedGateway: { width: 50, height: 50 }
}

interface FlowEdge {
  id: string
  from: string
  to: string
}

interface Point {
  x: number
  y: number
}

export function layoutBpmnXml(xml: string): string {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) {
    return xml
  }
  const process = doc.getElementsByTagNameNS(BPMN_NS, 'process')[0]
  if (!process) {
    return xml
  }
  const plane = ensurePlane(doc, process)
  if (!plane) {
    return xml
  }

  const nodeEls = Array.from(process.children).filter((el) => NODE_TYPES.has(el.localName))
  const flowEls = Array.from(process.children).filter((el) => el.localName === 'sequenceFlow')
  if (!nodeEls.length) {
    return xml
  }

  const shapeByRef = new Map<string, Element>()
  Array.from(plane.getElementsByTagNameNS(BPMNDI_NS, 'BPMNShape')).forEach((el) => {
    shapeByRef.set(el.getAttribute('bpmnElement') || '', el)
  })
  const edgeByRef = new Map<string, Element>()
  Array.from(plane.getElementsByTagNameNS(BPMNDI_NS, 'BPMNEdge')).forEach((el) => {
    edgeByRef.set(el.getAttribute('bpmnElement') || '', el)
  })

  const sizes = new Map<string, { width: number; height: number }>()
  for (const el of nodeEls) {
    const id = el.getAttribute('id') || ''
    const bounds = shapeByRef.get(id)?.getElementsByTagNameNS(DC_NS, 'Bounds')[0]
    const width = bounds ? Number(bounds.getAttribute('width')) : 0
    const height = bounds ? Number(bounds.getAttribute('height')) : 0
    sizes.set(id, width > 0 && height > 0
      ? { width, height }
      : (DEFAULT_SIZE[el.localName] || { width: 120, height: 80 }))
  }

  const edges: FlowEdge[] = flowEls.map((el) => ({
    id: el.getAttribute('id') || '',
    from: el.getAttribute('sourceRef') || '',
    to: el.getAttribute('targetRef') || ''
  })).filter((edge) => sizes.has(edge.from) && sizes.has(edge.to))

  const depths = bfsDepths(nodeEls, edges)
  const forward = edges.filter((edge) =>
    (depths.get(edge.to) ?? 0) > (depths.get(edge.from) ?? 0))
  const backward = edges.filter((edge) => !forward.includes(edge))

  const graph = new dagre.graphlib.Graph()
  graph.setGraph({ rankdir: 'LR', nodesep: 56, ranksep: 110, marginx: 40, marginy: 40 })
  graph.setDefaultEdgeLabel(() => ({}))
  for (const el of nodeEls) {
    const id = el.getAttribute('id') || ''
    graph.setNode(id, sizes.get(id)!)
  }
  for (const edge of forward) {
    graph.setEdge(edge.from, edge.to)
  }
  dagre.layout(graph)

  for (const el of nodeEls) {
    const id = el.getAttribute('id') || ''
    const center = graph.node(id)
    const size = sizes.get(id)!
    const shape = shapeByRef.get(id) || createShape(doc, id)
    writeBounds(doc, shape, center.x - size.width / 2, center.y - size.height / 2, size)
    if (!shape.parentNode) {
      plane.appendChild(shape)
    }
    shapeByRef.set(id, shape)
  }

  for (const edge of forward) {
    const points: Point[] = graph.edge(edge.from, edge.to)?.points || []
    writeEdge(doc, plane, edgeByRef, edge.id, points)
  }

  // 回退边从两节点下方的空白通道绕回，避免穿过节点
  for (const edge of backward) {
    const source = graph.node(edge.from)
    const target = graph.node(edge.to)
    const sourceSize = sizes.get(edge.from)!
    const targetSize = sizes.get(edge.to)!
    const bottom = Math.max(source.y + sourceSize.height / 2,
      target.y + targetSize.height / 2) + 60
    writeEdge(doc, plane, edgeByRef, edge.id, [
      { x: source.x, y: source.y + sourceSize.height / 2 },
      { x: source.x, y: bottom },
      { x: target.x, y: bottom },
      { x: target.x, y: target.y + targetSize.height / 2 }
    ])
  }

  return new XMLSerializer().serializeToString(doc)
}

function ensurePlane(doc: Document, process: Element): Element | null {
  const existing = doc.getElementsByTagNameNS(BPMNDI_NS, 'BPMNPlane')[0]
  if (existing) {
    return existing
  }
  let diagram = doc.getElementsByTagNameNS(BPMNDI_NS, 'BPMNDiagram')[0]
  if (!diagram) {
    const definitions = doc.getElementsByTagNameNS(BPMN_NS, 'definitions')[0]
      || doc.documentElement
    diagram = doc.createElementNS(BPMNDI_NS, 'bpmndi:BPMNDiagram')
    diagram.setAttribute('id', 'BPMNDiagram_1')
    definitions.appendChild(diagram)
  }
  const plane = doc.createElementNS(BPMNDI_NS, 'bpmndi:BPMNPlane')
  plane.setAttribute('id', 'BPMNPlane_1')
  plane.setAttribute('bpmnElement', process.getAttribute('id') || '')
  diagram.appendChild(plane)
  return plane
}

function createShape(doc: Document, id: string) {
  const shape = doc.createElementNS(BPMNDI_NS, 'bpmndi:BPMNShape')
  shape.setAttribute('id', `${id}_di`)
  shape.setAttribute('bpmnElement', id)
  shape.appendChild(doc.createElementNS(DC_NS, 'dc:Bounds'))
  return shape
}

function writeBounds(doc: Document, shape: Element, x: number, y: number,
                     size: { width: number; height: number }) {
  let bounds = shape.getElementsByTagNameNS(DC_NS, 'Bounds')[0]
  if (!bounds) {
    bounds = doc.createElementNS(DC_NS, 'dc:Bounds')
    shape.appendChild(bounds)
  }
  bounds.setAttribute('x', String(Math.round(x)))
  bounds.setAttribute('y', String(Math.round(y)))
  bounds.setAttribute('width', String(size.width))
  bounds.setAttribute('height', String(size.height))
}

function writeEdge(doc: Document, plane: Element, edgeByRef: Map<string, Element>,
                   id: string, points: Point[]) {
  const edge = edgeByRef.get(id) || createEdge(doc, id)
  // 只替换 waypoint，保留 BPMNLabel 等其它子元素
  Array.from(edge.getElementsByTagNameNS(DI_NS, 'waypoint'))
    .forEach((waypoint) => waypoint.parentNode?.removeChild(waypoint))
  for (const point of points) {
    const waypoint = doc.createElementNS(DI_NS, 'di:waypoint')
    waypoint.setAttribute('x', String(Math.round(point.x)))
    waypoint.setAttribute('y', String(Math.round(point.y)))
    edge.appendChild(waypoint)
  }
  if (!edge.parentNode) {
    plane.appendChild(edge)
  }
  edgeByRef.set(id, edge)
}

function createEdge(doc: Document, id: string) {
  const edge = doc.createElementNS(BPMNDI_NS, 'bpmndi:BPMNEdge')
  edge.setAttribute('id', `${id}_di`)
  edge.setAttribute('bpmnElement', id)
  return edge
}

/** 从 startEvent 做 BFS；回退边的目标已访问且层级更小，据此识别。 */
function bfsDepths(nodes: Element[], edges: FlowEdge[]) {
  const outgoing = new Map<string, string[]>()
  for (const edge of edges) {
    const list = outgoing.get(edge.from) || []
    list.push(edge.to)
    outgoing.set(edge.from, list)
  }
  const depth = new Map<string, number>()
  const start = nodes.find((el) => el.localName === 'startEvent')?.getAttribute('id')
  if (!start) {
    return depth
  }
  depth.set(start, 0)
  const queue = [start]
  while (queue.length) {
    const current = queue.shift()!
    for (const next of outgoing.get(current) || []) {
      if (depth.has(next)) {
        continue
      }
      depth.set(next, (depth.get(current) ?? 0) + 1)
      queue.push(next)
    }
  }
  return depth
}
