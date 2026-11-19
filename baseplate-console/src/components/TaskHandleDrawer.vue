<template>
  <el-drawer :model-value="modelValue" size="820px" :with-header="false"
             @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <div v-loading="loading" class="drawer-body">
      <template v-if="detail">
        <div class="detail-hero">
          <div class="hero-row">
            <div>
              <h3>{{ detail.task?.name || '任务办理' }}
                <span class="code-chip" style="margin-left: 8px">{{ detail.task?.activityId }}</span>
              </h3>
              <div class="sub">
                {{ detail.task?.processName || detail.task?.processKey }}
                <span style="margin: 0 8px">·</span><span class="mono">{{ detail.task?.businessKey || '—' }}</span>
                <span style="margin: 0 8px">·</span>发起人：{{ detail.task?.startUserName || nameOf(detail.task?.startUserId) }}
              </div>
            </div>
            <div class="hero-tags">
              <el-tag v-if="signText" size="small" effect="plain">{{ signText }}</el-tag>
              <el-tag :type="detail.canHandle ? 'warning' : detail.ended ? 'info' : 'success'" size="small">
                {{ detail.canHandle ? '待办理' : detail.ended ? '已办结' : '只读' }}
              </el-tag>
            </div>
          </div>
        </div>

        <el-alert v-if="!detail.canHandle && !detail.ended" type="info" :closable="false" show-icon
                  title="该任务当前不由你办理，以下内容为只读" style="margin-bottom: 14px" />

        <el-tabs v-model="tab">
          <el-tab-pane label="表单与审批" name="handle">
            <div v-if="formViews.length" class="form-wrap">
              <div v-for="form in formViews" :key="form.formCode" class="form-section">
                <div class="form-section-title">
                  <span>{{ form.formName || form.formCode }}</span>
                  <el-tag v-if="form.base" size="small" type="info" effect="plain">基础表单</el-tag>
                </div>
                <FormRenderer :ref="(el: any) => setRenderer(form.formCode, el)"
                              :rule="rulesOf(form.formCode)" :model-value="form.formData || {}"
                              :readonly="!detail.canHandle" />
              </div>
            </div>
            <el-empty v-else description="该节点没有配置表单，直接给出审批结论即可" :image-size="60" />

            <template v-if="detail.canHandle">
              <h4 class="sec-title">审批意见</h4>
              <el-input v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                        placeholder="填写审批意见；驳回 / 回退时建议写清原因，发起人能在进度里看到" />
              <div class="decision-row">
                <el-button v-for="btn in buttons" :key="btn.code"
                           :type="btn.code === 'REJECT' ? 'danger' : 'primary'"
                           :loading="submitting === btn.code" :disabled="!!submitting && submitting !== btn.code"
                           @click="submit(btn)">
                  {{ btn.label || (btn.code === 'REJECT' ? '驳回' : '同意') }}
                </el-button>
              </div>
              <div class="extra-actions">
                <span class="hint">更多操作：</span>
                <el-button link type="primary" @click="openTransfer">转办</el-button>
                <el-button link type="primary" @click="openAddSign">加签</el-button>
                <el-button v-if="auth.has('wf:rollback') && detail.rollbackTargets?.length"
                           link type="warning" @click="openRollback">回退到节点</el-button>
              </div>
            </template>

            <template v-else-if="doneInfo || detail.ended">
              <h4 class="sec-title">办理结果</h4>
              <dl class="kv">
                <dt>结论</dt>
                <dd>
                  <el-tag v-if="doneInfo?.decision" :type="doneInfo.decision === 'REJECT' ? 'danger' : 'success'" size="small">
                    {{ doneInfo.decision === 'REJECT' ? '驳回' : doneInfo.decision === 'APPROVE' ? '同意' : doneInfo.decision }}
                  </el-tag>
                  <span v-else class="hint">—</span>
                </dd>
                <dt>意见</dt><dd>{{ doneInfo?.reason || '—' }}</dd>
                <dt>办结时间</dt><dd>{{ fmt(doneInfo?.endedAt) }}</dd>
              </dl>
            </template>
          </el-tab-pane>

          <el-tab-pane label="流程进度" name="progress" lazy>
            <InstanceProgress v-if="detail.task?.processInstanceId" :instance-id="detail.task.processInstanceId" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
    <template #footer>
      <div class="drawer-foot">
        <span class="hint">到达时间：{{ fmt(detail?.task?.createdAt) }}</span>
        <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      </div>
    </template>
  </el-drawer>

  <el-dialog v-model="transferOpen" title="转办任务" width="440px" append-to-body>
      <p class="hint" style="margin: 0 0 12px">转办后该任务从你的待办移除，出现在对方的待办里。</p>
      <el-select v-model="transferTo" filterable placeholder="选择接收人" style="width: 100%">
        <el-option v-for="user in directoryUsers" :key="user.userId" :value="user.userId" :label="labelOf(user)" />
      </el-select>
      <template #footer>
        <el-button @click="transferOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" :disabled="!transferTo" @click="confirmTransfer">确认转办</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addSignOpen" title="加签" width="480px" append-to-body>
      <p class="hint" style="margin: 0 0 12px">为当前节点增加办理人，新办理人会收到待办；原办理人仍需办理。</p>
      <el-select v-model="addSignUsers" multiple filterable placeholder="选择加签人员（可多选）" style="width: 100%">
        <el-option v-for="user in directoryUsers" :key="user.userId" :value="user.userId" :label="labelOf(user)" />
      </el-select>
      <template #footer>
        <el-button @click="addSignOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" :disabled="!addSignUsers.length" @click="confirmAddSign">确认加签</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rollbackOpen" title="回退到历史节点" width="480px" append-to-body>
      <p class="hint" style="margin: 0 0 12px">流程将退回所选节点重新办理，中间节点会被标记为「被打回」。</p>
      <el-select v-model="rollbackTarget" placeholder="选择目标节点" style="width: 100%; margin-bottom: 12px">
        <el-option v-for="node in rollbackOptions" :key="node.id" :value="node.id" :label="node.label" />
      </el-select>
      <el-input v-model="rollbackReason" type="textarea" :rows="2" placeholder="回退原因（必填）" />
      <template #footer>
        <el-button @click="rollbackOpen = false">取消</el-button>
        <el-button type="warning" :loading="acting" :disabled="!rollbackTarget || !rollbackReason.trim()"
                   @click="confirmRollback">确认回退</el-button>
      </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'
import { useAuthStore } from '../stores/auth'
import { useWorkflowStore } from '../stores/workflow'
import { useUserDirectory } from '../composables/useUserDirectory'
import FormRenderer from '../views/FormRenderer.vue'
import InstanceProgress from './InstanceProgress.vue'

const props = defineProps<{
  modelValue: boolean
  taskId: string
  doneInfo?: { decision?: string; reason?: string; endedAt?: string } | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'handled'): void
}>()

const auth = useAuthStore()
const workflow = useWorkflowStore()
const { users: directoryUsers, ensureLoaded, nameOf, labelOf } = useUserDirectory()

const loading = ref(false)
const detail = ref<any>(null)
const tab = ref('handle')
const reason = ref('')
const submitting = ref('')
// 多表单：每个 formCode 一个 renderer 实例，提交时逐个校验+收集数据
const rendererRefs = new Map<string, InstanceType<typeof FormRenderer>>()
function setRenderer(code: string, el: any) {
  if (el) rendererRefs.set(code, el)
  else rendererRefs.delete(code)
}

const transferOpen = ref(false)
const transferTo = ref('')
const addSignOpen = ref(false)
const addSignUsers = ref<string[]>([])
const rollbackOpen = ref(false)
const rollbackTarget = ref('')
const rollbackReason = ref('')
const rollbackNodeNames = ref<Record<string, string>>({})
const acting = ref(false)

const SIGN_TEXT: Record<string, string> = {
  SINGLE: '单签', OR_SIGN: '或签', COUNTERSIGN: '会签', SEQUENTIAL: '依次签'
}
const signText = computed(() => {
  const config = detail.value?.nodeConfig
  if (!config?.signType || config.signType === 'SINGLE') return ''
  const base = SIGN_TEXT[config.signType] || config.signType
  return config.signType === 'COUNTERSIGN' && config.signRatio ? `${base} ${config.signRatio}%` : base
})

const buttons = computed(() => {
  const configured = detail.value?.nodeConfig?.decisionConfig?.buttons
  if (Array.isArray(configured) && configured.length) return configured
  return [
    { code: 'APPROVE', label: '同意', reasonRequired: false, reasonMinLength: 0 },
    { code: 'REJECT', label: '驳回', reasonRequired: true, reasonMinLength: 1 }
  ]
})

// 多表单视图：后端已按每份表单各自裁剪数据（HIDDEN 不下发值），
// 这里只做展示约束（HIDDEN 不渲染、READONLY 置灰），真正的防线在后端 FieldPolicyService。
const formViews = computed<any[]>(() => detail.value?.formDefinitions || [])

function parseUiSchema(raw: any): any[] {
  if (!raw) return []
  let parsed: any = raw
  if (typeof parsed === 'string') {
    try { parsed = JSON.parse(parsed) } catch { return [] }
  }
  const list: any[] = Array.isArray(parsed) ? parsed : parsed?.rule
  return Array.isArray(list) ? list : []
}

function rulesOf(code: string): any[] {
  const form = formViews.value.find((f) => f.formCode === code)
  if (!form) return []
  // 二维策略：先按 formCode 取子表，再按字段取权限
  const policy = detail.value?.nodeConfig?.fieldPolicy?.[code] || {}
  return parseUiSchema(form.formUiSchema)
    .filter((item: any) => policy[item.field] !== 'HIDDEN')
    .map((item: any) => policy[item.field] === 'READONLY'
      ? { ...item, props: { ...(item.props || {}), disabled: true } }
      : item)
}

const rollbackOptions = computed(() => (detail.value?.rollbackTargets || []).map((id: string) => ({
  id, label: rollbackNodeNames.value[id] ? `${rollbackNodeNames.value[id]}（${id}）` : id
})))

function fmt(value: any) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }

async function load() {
  if (!props.taskId) return
  loading.value = true
  detail.value = null
  reason.value = ''
  tab.value = 'handle'
  rendererRefs.clear()
  ensureLoaded()
  try {
    const { data } = await http.get(`/api/workflow/tasks/${props.taskId}/detail`)
    detail.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
    emit('update:modelValue', false)
  } finally {
    loading.value = false
  }
}

watch(() => [props.modelValue, props.taskId], ([open]) => { if (open && props.taskId) load() })

async function submit(button: any) {
  const text = reason.value.trim()
  const min = Math.max(button.reasonRequired ? 1 : 0, button.reasonMinLength || 0)
  if (min > 0 && text.length < min) {
    ElMessage.warning(`「${button.label || button.code}」需要填写至少 ${min} 个字的意见`)
    return
  }
  submitting.value = button.code
  try {
    // 逐个表单校验；只读表单 disabled 字段不参与填写，后端仍按权限裁剪
    for (const form of formViews.value) {
      const renderer: any = rendererRefs.get(form.formCode)
      if (renderer?.validate) {
        try { await renderer.validate() } catch {
          ElMessage.warning(`「${form.formName || form.formCode}」有未通过校验的字段，请先补全`)
          return
        }
      }
    }
    const values: Record<string, any> = { reason: text, comment: text }
    const formDataByCode: Record<string, any> = {}
    for (const form of formViews.value) {
      const renderer: any = rendererRefs.get(form.formCode)
      if (renderer?.formData) {
        formDataByCode[form.formCode] = renderer.formData()
      }
    }
    values.formDataByCode = formDataByCode
    await http.post(`/api/workflow/tasks/${props.taskId}/complete`, { decision: button.code, values })
    ElMessage.success(`已${button.label || (button.code === 'REJECT' ? '驳回' : '同意')}`)
    emit('handled')
    emit('update:modelValue', false)
    await workflow.refreshTodo()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    submitting.value = ''
  }
}

function openTransfer() { transferTo.value = ''; transferOpen.value = true }
function openAddSign() { addSignUsers.value = []; addSignOpen.value = true }

async function openRollback() {
  rollbackTarget.value = ''
  rollbackReason.value = ''
  rollbackOpen.value = true
  const instanceId = detail.value?.task?.processInstanceId
  if (!instanceId || Object.keys(rollbackNodeNames.value).length) return
  try {
    const { data } = await http.get(`/api/workflow/instances/${instanceId}/detail`)
    const names: Record<string, string> = {}
    for (const task of data.tasks || []) if (task.activityId && task.name) names[task.activityId] = task.name
    rollbackNodeNames.value = names
  } catch { /* 拿不到名称就只显示节点 ID */ }
}

async function confirmTransfer() {
  acting.value = true
  try {
    await http.post(`/api/workflow/tasks/${props.taskId}/transfer`, { toUserId: transferTo.value })
    ElMessage.success('已转办')
    transferOpen.value = false
    emit('handled')
    emit('update:modelValue', false)
    await workflow.refreshTodo()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { acting.value = false }
}

async function confirmAddSign() {
  acting.value = true
  try {
    await http.post(`/api/workflow/tasks/${props.taskId}/add-sign`, { userIds: addSignUsers.value })
    ElMessage.success('已加签')
    addSignOpen.value = false
    emit('handled')
    emit('update:modelValue', false)
    await workflow.refreshTodo()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { acting.value = false }
}

async function confirmRollback() {
  acting.value = true
  try {
    await http.post(`/api/workflow/tasks/${props.taskId}/rollback`,
      { targetNodeId: rollbackTarget.value, reason: rollbackReason.value.trim() })
    ElMessage.success('已回退')
    rollbackOpen.value = false
    emit('handled')
    emit('update:modelValue', false)
    await workflow.refreshTodo()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { acting.value = false }
}
</script>

<style scoped>
.drawer-body { padding: 4px 6px 10px; min-height: 200px; }
.hero-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.hero-tags { display: flex; gap: 8px; flex: none; }
.sec-title { margin: 18px 0 10px; font-size: 14px; }
.form-wrap { border: 1px solid var(--bp-line); border-radius: 12px; padding: 8px 20px 6px; background: #FBFCFB; }
.form-section { padding: 10px 0 14px; border-bottom: 1px dashed var(--bp-line); }
.form-section:last-child { border-bottom: none; }
.form-section-title { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 500; color: #1C1B18; margin-bottom: 10px; }
.decision-row { display: flex; gap: 12px; margin-top: 16px; }
.extra-actions { display: flex; align-items: center; gap: 4px; margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--bp-line); }
.drawer-foot { display: flex; align-items: center; justify-content: space-between; width: 100%; }
</style>