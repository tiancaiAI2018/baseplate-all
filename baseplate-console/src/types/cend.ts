export interface CendAnimation {
  name?: string
  duration?: number
  delay?: number
}

export interface CendBlock {
  id: string
  type: 'hero' | 'heading' | 'text' | 'image' | 'button' | 'card' | 'divider' | 'spacer' | 'form'
    | 'video' | 'carousel' | 'nav' | 'price' | 'icon' | 'countdown' | 'progress'
    | 'qrcode' | 'badge' | 'rating' | 'accordion' | 'table' | 'notice'
  props?: Record<string, any>
  style?: Record<string, any>
  animation?: CendAnimation
  children?: CendBlock[]
}

export interface CendTheme {
  primary?: string
  text?: string
  background?: string
  radius?: number
}

export interface CendCanvas {
  width?: number
  mode?: 'mobile' | 'desktop'
}

export interface CendPageDsl {
  canvas?: CendCanvas
  theme?: CendTheme
  blocks: CendBlock[]
}

export interface CendPageView {
  code: string
  name: string
  version: number
  schema: CendPageDsl
  uiSchema: CendPageDsl
}
