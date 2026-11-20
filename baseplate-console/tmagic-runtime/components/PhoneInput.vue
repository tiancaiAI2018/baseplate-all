<template>
  <div class="bp-phone-input" :style="config.style || {}">
    <span v-if="config.prefix" class="bp-phone-prefix">{{ config.prefix }}</span>
    <input
      :value="text"
      :placeholder="config.placeholder || '请输入手机号'"
      maxlength="11"
      inputmode="numeric"
      @input="onInput"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{ config: any }>()
const text = ref(String(props.config?.value || ''))

watch(() => props.config?.value, (value) => { text.value = String(value || '') })

function onInput(event: Event) {
  const value = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 11)
  text.value = value
}
</script>

<style scoped>
.bp-phone-input { display: flex; align-items: center; gap: 8px; box-sizing: border-box; }
.bp-phone-prefix { font-weight: 700; white-space: nowrap; }
.bp-phone-input input {
  flex: 1; min-width: 0; box-sizing: border-box; border: 1px solid rgba(255,255,255,.35);
  background: rgba(255,255,255,.14); color: #fff; border-radius: 12px;
  padding: 14px 16px; font-size: 16px; outline: none;
}
.bp-phone-input input::placeholder { color: rgba(255,255,255,.65); }
.bp-phone-input input:focus { border-color: #7dd3fc; box-shadow: 0 0 0 3px rgba(125,211,252,.2); }
</style>
