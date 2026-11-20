<template>
  <div
    class="cend-block"
    :class="[{ 'is-selected': editable && selectedId === block.id }, animationClass]"
    :style="blockStyle"
    :draggable="editable"
    @click.stop="editable && emit('select', block.id)"
    @dragstart.stop="editable && emit('dragstart', block.id)"
  >
    <template v-if="block.type === 'hero'">
      <div class="cend-hero" :style="heroStyle">
        <h1>{{ block.props?.title }}</h1>
        <p v-if="block.props?.subtitle">{{ block.props.subtitle }}</p>
        <img v-if="block.props?.image" :src="block.props.image" :alt="block.props?.alt || ''" />
        <CendBlockNode
          v-for="child in block.props?.children || []"
          :key="child.id"
          :block="child"
          :dsl="dsl"
          :editable="editable"
          :selected-id="selectedId"
          :page-code="pageCode"
          :submission-enabled="submissionEnabled"
          @select="(id) => emit('select', id)"
          @dragstart="(id) => emit('dragstart', id)"
        />
      </div>
    </template>
    <h2 v-else-if="block.type === 'heading'" :style="{ color: dsl.theme?.text }">
      {{ block.props?.text }}
    </h2>
    <p v-else-if="block.type === 'text'" class="cend-text" :style="{ color: dsl.theme?.text }">
      {{ block.props?.text }}
    </p>
    <img v-else-if="block.type === 'image'" class="cend-image" :src="block.props?.src" :alt="block.props?.alt || ''" />
    <button v-else-if="block.type === 'button'" class="cend-button" :class="`v-${block.props?.variant || 'primary'}`" type="button">
      {{ block.props?.text }}
    </button>
    <div v-else-if="block.type === 'card'" class="cend-card">
      <h3 v-if="block.props?.title">{{ block.props.title }}</h3>
      <p v-if="block.props?.text">{{ block.props.text }}</p>
      <CendBlockNode
        v-for="child in block.props?.children || []"
        :key="child.id"
        :block="child"
        :dsl="dsl"
        :editable="editable"
        :selected-id="selectedId"
        :page-code="pageCode"
        :submission-enabled="submissionEnabled"
        @select="(id) => emit('select', id)"
      />
    </div>
    <hr v-else-if="block.type === 'divider'" class="cend-divider" />
    <div v-else-if="block.type === 'spacer'" :style="{ height: `${block.props?.height || 24}px` }" />
    <form v-else-if="block.type === 'form'" class="cend-form" @submit.prevent="submit">
      <h3 v-if="block.props?.title">{{ block.props.title }}</h3>
      <div v-for="field in block.props?.fields || []" :key="field.name" class="cend-field">
        <label>{{ field.label || field.name }}<span v-if="field.required" class="cend-required">*</span></label>
        <select v-if="field.type === 'select'" v-model="formValues[field.name]">
          <option value="">请选择</option>
          <option v-for="option in field.options || []" :key="option" :value="option">{{ option }}</option>
        </select>
        <textarea v-else-if="field.type === 'textarea'" v-model="formValues[field.name]" :placeholder="field.placeholder || ''" />
        <input
          v-else
          v-model="formValues[field.name]"
          :type="field.type === 'number' ? 'number' : field.type === 'date' ? 'date' : 'text'"
          :placeholder="field.placeholder || ''"
        />
      </div>
      <button class="cend-button v-primary" type="submit" :disabled="!submissionEnabled || submitting">
        {{ submitting ? '提交中…' : (block.props?.submitText || '提交') }}
      </button>
      <p v-if="message" class="cend-message">{{ message }}</p>
    </form>
    <video v-else-if="block.type === 'video'" class="cend-video" :src="block.props?.src" :poster="block.props?.poster" :autoplay="block.props?.autoplay" controls />
    <div v-else-if="block.type === 'carousel'" class="cend-carousel">
      <img v-for="(src, index) in block.props?.images || []" :key="index" :src="src" :alt="`轮播图 ${index + 1}`" />
      <p v-if="!(block.props?.images || []).length" class="hint">请在右侧添加图片地址</p>
    </div>
    <div v-else-if="block.type === 'nav'" class="cend-nav">
      <a v-for="(item, index) in block.props?.items || []" :key="index" :href="item.url || '#'">{{ item.label || item.title }}</a>
    </div>
    <div v-else-if="block.type === 'price'" class="cend-price">
      <h3>{{ block.props?.title }}</h3>
      <div><s v-if="block.props?.originalPrice">{{ block.props.originalPrice }}</s> <b>{{ block.props?.price }}</b> <small>{{ block.props?.unit }}</small></div>
      <button class="cend-button v-primary" type="button">{{ block.props?.buttonText }}</button>
    </div>
    <div v-else-if="block.type === 'icon'" class="cend-icon" :style="{ color: block.props?.color, fontSize: `${block.props?.size || 48}px` }">
      {{ block.props?.icon }}
    </div>
    <div v-else-if="block.type === 'countdown'" class="cend-countdown">
      <span>{{ block.props?.label }}</span><b>{{ block.props?.deadline || '未设置截止时间' }}</b>
    </div>
    <div v-else-if="block.type === 'progress'" class="cend-progress">
      <div class="cend-progress-label">{{ block.props?.label }} · {{ block.props?.value }}%</div>
      <div class="cend-progress-track"><div :style="{ width: `${block.props?.value || 0}%` }" /></div>
    </div>
    <div v-else-if="block.type === 'qrcode'" class="cend-qrcode">
      <img v-if="block.props?.src" :src="block.props.src" alt="二维码" />
      <p>{{ block.props?.caption }}</p>
    </div>
    <span v-else-if="block.type === 'badge'" class="cend-badge" :class="`t-${block.props?.tone || 'primary'}`">{{ block.props?.text }}</span>
    <div v-else-if="block.type === 'rating'" class="cend-rating">★ {{ block.props?.value }} <small>({{ block.props?.count }} 人评分)</small></div>
    <div v-else-if="block.type === 'accordion'" class="cend-accordion">
      <details v-for="(item, index) in block.props?.items || []" :key="index">
        <summary>{{ item.title || item.label }}</summary>
        <p>{{ item.text }}</p>
      </details>
    </div>
    <table v-else-if="block.type === 'table'" class="cend-table">
      <thead><tr><th v-for="(header, index) in block.props?.headers || []" :key="index">{{ header }}</th></tr></thead>
      <tbody>
        <tr v-for="(row, rowIndex) in block.props?.rows || []" :key="rowIndex">
          <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
        </tr>
      </tbody>
    </table>
    <div v-else-if="block.type === 'notice'" class="cend-notice" :class="`t-${block.props?.tone || 'info'}`">{{ block.props?.text }}</div>
    <component v-else-if="customRenderer" :is="customRenderer" :block="block" :dsl="dsl" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../../api/client'
import { customCendRenderers } from './cendDsl'
import type { CendBlock, CendPageDsl } from '../../types/cend'

const customRenderers = customCendRenderers()

const props = defineProps<{
  block: CendBlock
  dsl: CendPageDsl
  editable?: boolean
  selectedId?: string
  pageCode?: string
  submissionEnabled?: boolean
}>()
const emit = defineEmits<{ (e: 'select', id: string): void; (e: 'dragstart', id: string): void }>()

const formValues = reactive<Record<string, any>>({})
const submitting = ref(false)
const message = ref('')

const customRenderer = computed(() => customRenderers[props.block.type])

const animationClass = computed(() => {
  const name = props.block.animation?.name || 'none'
  return name === 'none' ? '' : `bp-anim-${name}`
})

const blockStyle = computed(() => {
  const style: Record<string, any> = {
    borderRadius: `${props.dsl.theme?.radius ?? 12}px`,
    ...(props.block.style || {})
  }
  const animation = props.block.animation
  if (animation?.name && animation.name !== 'none') {
    style.animationDuration = `${animation.duration || 600}ms`
    style.animationDelay = `${animation.delay || 0}ms`
    style.animationFillMode = 'both'
  }
  return style
})

const heroStyle = computed(() => ({
  background: props.block.style?.background || `linear-gradient(135deg, ${props.dsl.theme?.primary || '#10b981'}, #0ea5e9)`,
  color: '#fff'
}))

async function submit() {
  if (!props.submissionEnabled) {
    message.value = '预览模式不会真正提交'
    return
  }
  const fields = props.block.props?.fields || []
  for (const field of fields) {
    if (field.required && !String(formValues[field.name] ?? '').trim()) {
      ElMessage.warning(`请填写${field.label || field.name}`)
      return
    }
  }
  submitting.value = true
  try {
    await http.post(`/api/c/pages/${props.pageCode}/submit`, {
      formCode: props.block.props?.formCode || undefined,
      payload: { ...formValues }
    })
    message.value = '提交成功，谢谢！'
  } catch (error: any) {
    message.value = error?.response?.data?.detail || '提交失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>
