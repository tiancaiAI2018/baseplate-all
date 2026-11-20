export interface CendDesignerBridge {
  code: () => string
  getDsl: () => any
  setDsl: (dsl: any) => void
  screenshot: () => Promise<string>
  selectedId: () => string
}

let bridge: CendDesignerBridge | null = null

export function registerCendDesigner(value: CendDesignerBridge) {
  bridge = value
  return () => {
    if (bridge === value) bridge = null
  }
}

export function getCendDesigner() {
  return bridge
}
