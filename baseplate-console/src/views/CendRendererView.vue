<template>
  <div class="cend-public">
    <iframe :src="pageUrl" frameborder="0" style="width: 100%; height: 100vh; background: #fff" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const code = String(route.params.code || '')
const tenant = String(route.query.tenant || 'default')

const pageUrl = computed(() =>
  `/tmagic-runtime/page.html?code=${encodeURIComponent(code)}&tenant=${encodeURIComponent(tenant)}`
)
</script>

<style scoped>
.cend-public { width: 100%; height: 100vh; }
</style>
