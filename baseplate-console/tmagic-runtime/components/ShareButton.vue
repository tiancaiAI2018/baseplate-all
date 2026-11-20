<template>
  <button class="bp-share-button" :style="config.style || {}" type="button" @click="copy">
    {{ copied ? '链接已复制' : (config.text || '分享活动链接') }}
  </button>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ config: any }>()
const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(location.href)
    copied.value = true
    window.setTimeout(() => { copied.value = false }, 2000)
  } catch {
    copied.value = false
  }
}
</script>

<style scoped>
.bp-share-button { border: none; cursor: pointer; }
</style>
