<template>
  <el-drawer :model-value="modelValue" size="760px" :with-header="false"
             @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <div class="drawer-body">
      <div class="detail-hero">
        <div class="hero-row">
          <div>
            <h3>{{ detail?.processDefinitionName || '流程进度' }}
              <el-tag v-if="detail?.version" size="small" effect="plain" style="margin-left: 6px">v{{ detail.version }}</el-tag>
            </h3>
            <div class="sub">
              <span class="mono">{{ detail?.businessKey || '—' }}</span>
              <span style="margin: 0 8px">·</span>
              <span class="mono" :title="instanceId">{{ shortId }}</span>
            </div>
          </div>
          <el-button @click="reload">刷新</el-button>
        </div>
      </div>
      <InstanceProgress v-if="instanceId" :key="instanceId" ref="progressRef" :instance-id="instanceId"
                        @loaded="onLoaded" />
    </div>
    <template #footer>
      <div class="drawer-foot">
        <span class="hint">进度、表单与操作流水实时来自流程引擎</span>
        <div style="display: flex; gap: 10px">
          <el-button v-if="canWithdraw" type="warning" plain :loading="withdrawing" @click="withdraw">撤回流程</el-button>
          <el-button @click="emit('update:modelValue', false)">关闭</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'
import { useWorkflowStore } from '../stores/workflow'
import InstanceProgress from './InstanceProgress.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  instanceId: string
  allowWithdraw?: boolean
}>(), { allowWithdraw: false })
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'withdrawn'): void
}>()

const auth = useAuthStore()
const workflow = useWorkflowStore()
const detail = ref<any>(null)
const withdrawing = ref(false)
const progressRef = ref<InstanceType<typeof InstanceProgress> | null>(null)

const shortId = computed(() => props.instanceId ? props.instanceId.slice(0, 8) : '—')
const canWithdraw = computed(() => props.allowWithdraw && detail.value && !detail.value.ended
  && (detail.value.startUserId === auth.user?.userId || auth.isSuperAdmin || auth.has('wf:manage')))

function onLoaded(value: any) { detail.value = value }
function reload() { progressRef.value?.reload() }

async function withdraw() {
  try {
    const { value } = await ElMessageBox.prompt(
      '撤回后流程立即结束，审批人不会再收到待办。可以填写撤回原因：', '撤回流程',
      { inputPlaceholder: '如：信息填错，重新发起', confirmButtonText: '确认撤回', type: 'warning' })
    withdrawing.value = true
    await http.post(`/api/workflow/instances/${props.instanceId}/withdraw`, { reason: value || '' })
    ElMessage.success('已撤回')
    emit('withdrawn')
    await workflow.refreshTodo()
    reload()
  } catch (error: any) {
    if (error !== 'cancel' && error?.message !== 'cancel') ElMessage.error(errorText(error))
  } finally {
    withdrawing.value = false
  }
}
</script>

<style scoped>
.drawer-body { padding: 4px 6px 10px; }
.hero-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.drawer-foot { display: flex; align-items: center; justify-content: space-between; width: 100%; }
</style>