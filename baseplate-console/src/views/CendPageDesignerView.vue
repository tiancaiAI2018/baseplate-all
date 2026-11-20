<template>
  <div class="page cend-designer">
    <div class="page-head">
      <div>
        <h2 class="page-title">C 端页面设计</h2>
        <p class="page-desc">左侧添加区块，中间编排页面，右侧调整内容与动画；AI 生成的内容会直接打开在这里。</p>
      </div>
      <div class="page-actions">
        <el-button @click="previewOpen = true">预览</el-button>
        <el-button @click="saveDraft">保存草稿</el-button>
        <el-button type="primary" @click="publish">保存并发布</el-button>
      </div>
    </div>

    <div class="card toolbar">
      <el-input v-model="code" placeholder="页面编码" style="width: 220px" />
      <el-input v-model="name" placeholder="页面名称" style="width: 220px" />
      <span class="hint">版本 {{ version }} · {{ dsl.blocks.length }} 个区块</span>
    </div>

    <el-row :gutter="12" class="designer-row">
      <el-col :span="4">
        <div class="card side">
          <BlockPalette @add="addBlock" @dragtype="dragType = $event" />
        </div>
      </el-col>
      <el-col :span="13">
        <div class="card canvas-wrap">
          <div
            class="canvas-stage"
            @dragover.prevent
            @drop.prevent="dropOnCanvas"
          >
            <PageRenderer
              :dsl="dsl"
              editable
              :selected-id="selectedId"
              :page-code="code"
              :submission-enabled="false"
              @select="selectedId = $event"
              @dragstart="dragId = $event"
            />
          </div>
        </div>
      </el-col>
      <el-col :span="7">
        <div class="card side">
          <Inspector
            :block="selectedBlock"
            :dsl="dsl"
            @update:block="updateBlock"
            @update:dsl="dsl = $event"
            @remove="removeBlock"
          />
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="previewOpen" title="页面预览" width="460px">
      <div class="preview-stage"><PageRenderer :dsl="dsl" :page-code="code" :submission-enabled="false" /></div>
      <template #footer><el-button @click="previewOpen = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { errorText } from '../api/client'
import { registerAgentCanvas } from '../api/agentCanvas'
import BlockPalette from '../components/cend/BlockPalette.vue'
import PageRenderer from '../components/cend/PageRenderer.vue'
import Inspector from '../components/cend/Inspector.vue'
import { defaultPropsFor, emptyDsl, newId, normalizeAiPage, schemaOf } from '../components/cend/cendDsl'
import type { CendPageDsl } from '../types/cend'

const route = useRoute()
const code = ref(String(route.query.code || ''))
const name = ref('')
const version = ref(0)
const dsl = ref<CendPageDsl>(emptyDsl())
const selectedId = ref('')
const dragType = ref('')
const dragId = ref('')
const previewOpen = ref(false)
let unregisterCanvas: (() => void) | null = null

const selectedBlock = computed(() => dsl.value.blocks.find((block) => block.id === selectedId.value) || null)

async function load(target = code.value) {
  if (!target) return
  try {
    const { data } = await http.get(`/api/metadata/CEND_PAGE/${target}`)
    code.value = data.code
    name.value = data.name || data.code
    version.value = data.version
    const stored = data.uiSchemaJson ?? data.uiSchema
    const raw = typeof stored === 'string' ? JSON.parse(stored) : stored
    dsl.value = normalizeAiPage(raw?.blocks ? raw : { blocks: [] })
    selectedId.value = ''
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function addBlock(type: string) {
  const block = {
    id: newId(),
    type: type as any,
    props: defaultPropsFor(type),
    style: {},
    animation: { name: 'fadeInUp', duration: 600, delay: 0 }
  }
  dsl.value.blocks.push(block)
  selectedId.value = block.id
}

function dropOnCanvas() {
  if (dragType.value) {
    addBlock(dragType.value)
    dragType.value = ''
    return
  }
  if (dragId.value) {
    const index = dsl.value.blocks.findIndex((block) => block.id === dragId.value)
    if (index >= 0) {
      const moved = dsl.value.blocks.splice(index, 1)[0]
      dsl.value.blocks.push(moved)
      selectedId.value = moved.id
    }
    dragId.value = ''
  }
}

function updateBlock(block: any) {
  const index = dsl.value.blocks.findIndex((item) => item.id === block.id)
  if (index >= 0) dsl.value.blocks.splice(index, 1, block)
  dsl.value = { ...dsl.value, blocks: [...dsl.value.blocks] }
}

function removeBlock() {
  if (!selectedId.value) return
  dsl.value.blocks = dsl.value.blocks.filter((block) => block.id !== selectedId.value)
  selectedId.value = ''
}

async function saveDraft() {
  if (!code.value) {
    ElMessage.warning('请先填写页面编码')
    return
  }
  try {
    const { data } = await http.post(`/api/metadata/CEND_PAGE/${code.value}`, {
      name: name.value || code.value,
      schema: schemaOf(dsl.value),
      uiSchema: dsl.value
    })
    version.value = data.version
    ElMessage.success(`草稿已保存，版本 v${data.version}`)
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function publish() {
  await saveDraft()
  try {
    await http.post(`/api/metadata/CEND_PAGE/${code.value}/publish`, null, { params: { version: version.value } })
    ElMessage.success('已发布，公开链接可访问')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

watch(
  () => [route.query.code, route.query.v],
  ([value]) => { if (value) void load(String(value)) }
)

onMounted(() => {
  if (!code.value) {
    code.value = `page_${Date.now().toString(36)}`
    name.value = '未命名 C 端页面'
  }
  void load()
  unregisterCanvas = registerAgentCanvas('cend/pages/design', () => ({
    available: true,
    data: { code: code.value, name: name.value, dsl: dsl.value, blockCount: dsl.value.blocks.length }
  }))
})

onBeforeUnmount(() => unregisterCanvas?.())
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.designer-row { align-items: stretch; }
.side { padding: 12px; min-height: 640px; max-height: 760px; overflow: auto; }
.canvas-wrap { padding: 12px; min-height: 640px; overflow: auto; background: #f6f8f7; }
.canvas-stage { min-height: 600px; }
.cend-block { cursor: pointer; }
.preview-stage { max-height: 560px; overflow: auto; background: #f6f8f7; padding: 10px; }
</style>
