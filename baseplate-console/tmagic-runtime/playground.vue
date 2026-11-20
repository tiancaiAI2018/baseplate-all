<template>
  <component v-if="pageConfig" :is="pageComponent" :key="pageConfig.id" :config="pageConfig" />
</template>

<script setup lang="ts">
import { useComponent, useEditorDsl } from '@tmagic/vue-runtime-help'

const { pageConfig } = useEditorDsl()
const pageComponent = useComponent('page')
</script>
