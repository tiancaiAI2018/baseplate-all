<template>
  <div class="inspector">
    <div v-if="!block" class="section">
      <div class="section-title">页面设置</div>
      <el-form label-width="88px" size="small">
        <el-form-item label="画布宽度">
          <el-input-number v-model="canvasWidth" :min="280" :max="1200" @change="setCanvas('width', canvasWidth)" />
        </el-form-item>
        <el-form-item label="预览设备">
          <el-radio-group :model-value="dsl.canvas?.mode || 'mobile'" @change="setCanvas('mode', $event)">
            <el-radio-button value="mobile">手机</el-radio-button>
            <el-radio-button value="desktop">桌面</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="主题色"><el-color-picker :model-value="dsl.theme?.primary || '#10b981'" @change="setTheme('primary', $event)" /></el-form-item>
        <el-form-item label="正文色"><el-color-picker :model-value="dsl.theme?.text || '#1f2937'" @change="setTheme('text', $event)" /></el-form-item>
        <el-form-item label="背景色"><el-color-picker :model-value="dsl.theme?.background || '#ffffff'" @change="setTheme('background', $event)" /></el-form-item>
        <el-form-item label="圆角">
          <el-input-number :model-value="dsl.theme?.radius || 12" :min="0" :max="48" @change="setTheme('radius', $event)" />
        </el-form-item>
      </el-form>
      <p class="hint">选中画布中的区块后，这里会切换为区块属性。</p>
    </div>

    <template v-else>
      <div class="section">
        <div class="section-title">{{ typeLabel }} · 内容</div>
        <el-form label-width="80px" size="small">
          <el-form-item v-for="field in textFields" :key="field.key" :label="field.label">
            <el-input :model-value="String(block.props?.[field.key] ?? '')" @input="setProp(field.key, $event)" />
          </el-form-item>
          <el-form-item v-if="block.type === 'image'" label="图片地址">
            <el-input :model-value="String(block.props?.src || '')" @input="setProp('src', $event)" />
          </el-form-item>
          <el-form-item v-if="block.type === 'button'" label="样式">
            <el-select :model-value="block.props?.variant || 'primary'" @change="setProp('variant', $event)">
              <el-option label="主色" value="primary" />
              <el-option label="次色" value="secondary" />
              <el-option label="描边" value="ghost" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="block.type === 'spacer'" label="高度">
            <el-input-number :model-value="Number(block.props?.height || 24)" :min="0" :max="400" @change="setProp('height', $event)" />
          </el-form-item>
          <el-form-item v-if="block.type === 'form'" label="绑定表单">
            <el-input :model-value="String(block.props?.formCode || '')" @input="setProp('formCode', $event)" placeholder="流程表单编码，可留空" />
          </el-form-item>
        </el-form>
      </div>

      <div v-if="block.type === 'form'" class="section">
        <div class="section-title">内嵌字段</div>
        <div v-for="(field, index) in block.props?.fields || []" :key="index" class="field-row">
          <el-input v-model="field.name" placeholder="字段名" style="width: 96px" />
          <el-input v-model="field.label" placeholder="标题" style="width: 96px" />
          <el-select v-model="field.type" style="width: 90px">
            <el-option v-for="t in ['input', 'textarea', 'number', 'date', 'select']" :key="t" :label="t" :value="t" />
          </el-select>
          <el-button link type="danger" @click="removeField(index)">删</el-button>
        </div>
        <el-button size="small" @click="addField">添加字段</el-button>
      </div>

      <div class="section">
        <div class="section-title">样式</div>
        <el-form label-width="80px" size="small">
          <el-form-item label="文字颜色"><el-color-picker :model-value="String(block.style?.color || '')" @change="setStyle('color', $event)" /></el-form-item>
          <el-form-item label="背景"><el-input :model-value="String(block.style?.background || '')" @input="setStyle('background', $event)" placeholder="支持渐变/CSS 颜色" /></el-form-item>
          <el-form-item label="字号"><el-input-number :model-value="Number(block.style?.fontSize || 0)" :min="0" :max="72" @change="setStyle('fontSize', $event)" /></el-form-item>
          <el-form-item label="内边距"><el-input :model-value="String(block.style?.padding || '')" @input="setStyle('padding', $event)" placeholder="如 20px 16px" /></el-form-item>
          <el-form-item label="对齐">
            <el-select :model-value="String(block.style?.textAlign || '')" @change="setStyle('textAlign', $event)">
              <el-option label="左" value="left" /><el-option label="中" value="center" /><el-option label="右" value="right" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <div class="section">
        <div class="section-title">动画</div>
        <el-form label-width="80px" size="small">
          <el-form-item label="效果">
            <el-select :model-value="block.animation?.name || 'none'" @change="setAnimation('name', $event)">
              <el-option v-for="name in ANIMATIONS" :key="name" :label="name" :value="name" />
            </el-select>
          </el-form-item>
          <el-form-item label="时长(ms)">
            <el-input-number :model-value="Number(block.animation?.duration || 600)" :min="0" :max="5000" :step="100" @change="setAnimation('duration', $event)" />
          </el-form-item>
          <el-form-item label="延迟(ms)">
            <el-input-number :model-value="Number(block.animation?.delay || 0)" :min="0" :max="5000" :step="100" @change="setAnimation('delay', $event)" />
          </el-form-item>
        </el-form>
      </div>

      <el-button type="danger" plain size="small" @click="emit('remove')">删除区块</el-button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ANIMATIONS, BLOCK_TYPES } from './cendDsl'
import type { CendBlock, CendPageDsl } from '../../types/cend'

const props = defineProps<{ block: CendBlock | null; dsl: CendPageDsl }>()
const emit = defineEmits<{ (e: 'update:block', block: CendBlock): void; (e: 'update:dsl', dsl: CendPageDsl): void; (e: 'remove'): void }>()

const canvasWidth = ref(props.dsl.canvas?.width || 375)
watch(() => props.dsl.canvas?.width, (value) => { canvasWidth.value = value || 375 })

const typeLabel = computed(() => BLOCK_TYPES.find((item) => item.type === props.block?.type)?.label || '区块')
const textFields = computed(() => {
  const type = props.block?.type
  if (type === 'heading') return [{ key: 'text', label: '标题' }]
  if (type === 'text') return [{ key: 'text', label: '正文' }]
  if (type === 'hero') return [{ key: 'title', label: '主标题' }, { key: 'subtitle', label: '副标题' }, { key: 'image', label: '背景图' }]
  if (type === 'button') return [{ key: 'text', label: '按钮文字' }, { key: 'url', label: '跳转链接' }]
  if (type === 'card') return [{ key: 'title', label: '卡片标题' }, { key: 'text', label: '卡片内容' }]
  if (type === 'form') return [{ key: 'title', label: '表单标题' }]
  if (type === 'video') return [{ key: 'src', label: '视频地址' }, { key: 'poster', label: '封面图' }]
  if (type === 'icon') return [{ key: 'icon', label: '图标字符' }, { key: 'color', label: '颜色' }]
  if (type === 'countdown') return [{ key: 'label', label: '提示文字' }, { key: 'deadline', label: '截止时间' }]
  if (type === 'progress') return [{ key: 'label', label: '进度名称' }]
  if (type === 'qrcode') return [{ key: 'src', label: '二维码图片' }, { key: 'caption', label: '说明' }]
  if (type === 'badge') return [{ key: 'text', label: '标签文字' }]
  if (type === 'price') return [{ key: 'title', label: '套餐名' }, { key: 'price', label: '价格' }, { key: 'originalPrice', label: '原价' }, { key: 'buttonText', label: '按钮文字' }]
  if (type === 'notice') return [{ key: 'text', label: '公告内容' }]
  return []
})

function patchBlock(patch: Record<string, any>) {
  if (!props.block) return
  emit('update:block', { ...props.block, ...patch })
}

function setProp(key: string, value: any) {
  if (!props.block) return
  patchBlock({ props: { ...(props.block.props || {}), [key]: value } })
}

function setStyle(key: string, value: any) {
  if (!props.block) return
  patchBlock({ style: { ...(props.block.style || {}), [key]: value } })
}

function setAnimation(key: string, value: any) {
  if (!props.block) return
  patchBlock({ animation: { ...(props.block.animation || {}), [key]: value } })
}

function setTheme(key: string, value: any) {
  emit('update:dsl', { ...props.dsl, theme: { ...(props.dsl.theme || {}), [key]: value } })
}

function setCanvas(key: string, value: any) {
  emit('update:dsl', { ...props.dsl, canvas: { ...(props.dsl.canvas || {}), [key]: value } })
}

function addField() {
  if (!props.block) return
  const fields = [...(props.block.props?.fields || []), { name: `field_${Date.now().toString(36)}`, label: '新字段', type: 'input', required: false, options: [] }]
  setProp('fields', fields)
}

function removeField(index: number) {
  if (!props.block) return
  const fields = [...(props.block.props?.fields || [])]
  fields.splice(index, 1)
  setProp('fields', fields)
}
</script>

<style scoped>
.inspector { padding: 4px 2px; }
.section { margin-bottom: 16px; }
.section-title { font-weight: 700; margin-bottom: 8px; }
.field-row { display: flex; gap: 4px; align-items: center; margin-bottom: 6px; }
</style>
