<template>
  <div class="cend-page" :style="{ width: `${dsl.canvas?.width || 375}px`, background: dsl.theme?.background || '#fff' }">
    <CendBlockNode
      v-for="block in dsl.blocks"
      :key="block.id"
      :block="block"
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

<script setup lang="ts">
import CendBlockNode from './CendBlockNode.vue'
import type { CendPageDsl } from '../../types/cend'

defineProps<{
  dsl: CendPageDsl
  editable?: boolean
  selectedId?: string
  pageCode?: string
  submissionEnabled?: boolean
}>()
const emit = defineEmits<{ (e: 'select', id: string): void; (e: 'dragstart', id: string): void }>()
</script>

<style>
@keyframes bp-fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes bp-fadeInUp { from { opacity: 0; transform: translateY(24px); } to { opacity: 1; transform: none; } }
@keyframes bp-fadeInDown { from { opacity: 0; transform: translateY(-24px); } to { opacity: 1; transform: none; } }
@keyframes bp-slideInLeft { from { opacity: 0; transform: translateX(-40px); } to { opacity: 1; transform: none; } }
@keyframes bp-slideInRight { from { opacity: 0; transform: translateX(40px); } to { opacity: 1; transform: none; } }
@keyframes bp-zoomIn { from { opacity: 0; transform: scale(.86); } to { opacity: 1; transform: none; } }
@keyframes bp-bounceIn { 0% { opacity: 0; transform: scale(.6); } 60% { opacity: 1; transform: scale(1.06); } 100% { transform: none; } }
.bp-anim-fadeIn { animation-name: bp-fadeIn; }
.bp-anim-fadeInUp { animation-name: bp-fadeInUp; }
.bp-anim-fadeInDown { animation-name: bp-fadeInDown; }
.bp-anim-slideInLeft { animation-name: bp-slideInLeft; }
.bp-anim-slideInRight { animation-name: bp-slideInRight; }
.bp-anim-zoomIn { animation-name: bp-zoomIn; }
.bp-anim-bounceIn { animation-name: bp-bounceIn; }

.cend-page { margin: 0 auto; min-height: 640px; overflow: hidden; box-shadow: 0 8px 30px rgba(15, 23, 42, .12); }
.cend-block { position: relative; padding: 12px 16px; cursor: default; }
.cend-block.is-selected { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.cend-hero { border-radius: inherit; padding: 56px 24px; text-align: center; background-size: cover; background-position: center; }
.cend-hero h1 { margin: 0 0 10px; font-size: 30px; }
.cend-hero p { margin: 0; opacity: .9; }
.cend-hero img { max-width: 100%; margin-top: 16px; border-radius: 12px; }
.cend-text { white-space: pre-wrap; line-height: 1.7; }
.cend-image { display: block; max-width: 100%; margin: 0 auto; border-radius: 12px; }
.cend-button { border: none; border-radius: 999px; padding: 11px 22px; font-weight: 600; cursor: pointer; color: #fff; background: var(--el-color-primary); }
.cend-button.v-secondary { background: #0ea5e9; }
.cend-button.v-ghost { background: transparent; color: var(--el-color-primary); border: 1px solid currentColor; }
.cend-card { border: 1px solid var(--bp-line, #e5e7eb); border-radius: inherit; padding: 16px; }
.cend-card h3 { margin: 0 0 8px; }
.cend-divider { border: none; border-top: 1px solid var(--bp-line, #e5e7eb); margin: 8px 0; }
.cend-form { display: flex; flex-direction: column; gap: 10px; }
.cend-form h3 { margin: 0 0 4px; }
.cend-field { display: flex; flex-direction: column; gap: 4px; text-align: left; }
.cend-field label { font-size: 13px; font-weight: 600; }
.cend-required { color: #ef4444; margin-left: 2px; }
.cend-field input, .cend-field select, .cend-field textarea {
  border: 1px solid var(--bp-line, #d1d5db); border-radius: 8px; padding: 8px 10px; font: inherit; background: #fff;
}
.cend-field textarea { min-height: 70px; resize: vertical; }
.cend-message { font-size: 13px; color: var(--el-color-primary); margin: 4px 0 0; }
.cend-video { width: 100%; border-radius: 12px; }
.cend-carousel { display: flex; gap: 8px; overflow-x: auto; }
.cend-carousel img { width: 160px; height: 110px; object-fit: cover; border-radius: 10px; flex: none; }
.cend-nav { display: flex; gap: 18px; justify-content: center; }
.cend-nav a { color: inherit; text-decoration: none; font-weight: 600; }
.cend-price { text-align: center; }
.cend-price s { color: #94a3b8; }
.cend-price b { font-size: 30px; color: var(--el-color-primary); }
.cend-price small { color: #64748b; }
.cend-price button { margin-top: 10px; }
.cend-icon { text-align: center; line-height: 1.2; }
.cend-countdown { display: flex; justify-content: space-between; align-items: center; background: #fff7ed; padding: 12px 14px; border-radius: 10px; }
.cend-countdown b { color: #ea580c; }
.cend-progress-label { font-size: 13px; margin-bottom: 6px; }
.cend-progress-track { height: 8px; border-radius: 999px; background: #e2e8f0; overflow: hidden; }
.cend-progress-track div { height: 100%; background: var(--el-color-primary); }
.cend-qrcode { text-align: center; }
.cend-qrcode img { width: 140px; height: 140px; object-fit: contain; }
.cend-badge { display: inline-block; padding: 3px 10px; border-radius: 999px; background: var(--el-color-primary); color: #fff; font-size: 12px; }
.cend-badge.t-warning { background: #f59e0b; }
.cend-badge.t-danger { background: #ef4444; }
.cend-badge.t-info { background: #0ea5e9; }
.cend-rating { text-align: center; color: #f59e0b; font-size: 20px; font-weight: 700; }
.cend-rating small { color: #64748b; font-size: 12px; font-weight: 400; }
.cend-accordion details { border-bottom: 1px solid var(--bp-line, #e5e7eb); padding: 8px 0; }
.cend-accordion summary { cursor: pointer; font-weight: 600; }
.cend-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.cend-table th, .cend-table td { border: 1px solid var(--bp-line, #e5e7eb); padding: 6px 8px; text-align: left; }
.cend-notice { padding: 10px 14px; border-radius: 10px; background: #ecfdf5; color: #047857; }
.cend-notice.t-warning { background: #fffbeb; color: #b45309; }
.cend-notice.t-danger { background: #fef2f2; color: #b91c1c; }
.hint { color: #94a3b8; font-size: 12px; }
</style>
