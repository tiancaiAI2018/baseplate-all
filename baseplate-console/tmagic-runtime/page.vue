<template>
  <component :is="pageComponent" :config="pageConfig" />
</template>

<script setup lang="ts">
import type { MPage } from '@tmagic/core'
import { useComponent, useDsl } from '@tmagic/vue-runtime-help'

const { pageConfig } = useDsl()
const pageComponent = useComponent('page')
</script>

<script lang="ts">
export default { name: 'CendPageRuntime' }
</script>
