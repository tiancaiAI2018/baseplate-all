<template>
  <el-dialog :model-value="modelValue" :title="`发起配置 · ${processName || processKey}`" width="620px"
             destroy-on-close @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <div v-loading="loading">
      <el-form label-position="top">
        <div class="setting-grid">
          <el-form-item label="分类">
            <el-select v-model="form.category" filterable allow-create default-first-option
                       placeholder="如：行政办公" style="width: 100%">
              <el-option v-for="cat in CATEGORY_PRESETS" :key="cat" :value="cat" :label="cat" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序（越小越靠前）">
            <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>

        <el-form-item label="图标">
          <div class="icon-picker">
            <button v-for="emoji in ICON_PRESETS" :key="emoji" type="button" class="icon-cell"
                    :class="{ active: form.icon === emoji }" @click="form.icon = emoji">{{ emoji }}</button>
            <el-input v-model="form.icon" placeholder="或输入" style="width: 90px" maxlength="4" />
          </div>
        </el-form-item>

        <el-form-item label="流程说明（展示在发起卡片上）">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="120" show-word-limit
                    placeholder="一句话说明这个流程用来做什么、什么时候发起" />
        </el-form-item>

        <el-form-item label="基础表单">
          <el-select v-model="form.baseFormCodes" multiple filterable collapse-tags collapse-tags-tooltip
                     :max-collapse-tags="2" placeholder="所有节点自动继承，节点只能新增不能删除" style="width: 100%">
            <el-option v-for="item in options.forms" :key="item.code" :value="item.code"
                       :label="`${item.name}（${item.code} v${item.version}）`" />
          </el-select>
          <p class="hint" style="margin: 6px 0 0">
            可多选。这里配的表单会出现在每一个节点上且节点端无法删除；留空时默认取首个任务节点绑定的表单。
          </p>
        </el-form-item>

        <el-form-item label="谁可以发起（发起侧数据权限）">
          <el-radio-group v-model="form.startScopeType" @change="onScopeTypeChange">
            <el-radio value="ALL">所有人</el-radio>
            <el-radio value="ROLE">指定角色</el-radio>
            <el-radio value="USER">指定人员</el-radio>
            <el-radio value="ORG">指定组织</el-radio>
          </el-radio-group>
          <div v-if="form.startScopeType !== 'ALL'" class="scope-values">
            <el-select v-if="form.startScopeType === 'ROLE'" v-model="scopeValues" multiple filterable
                       placeholder="选择角色" style="width: 100%">
              <el-option v-for="role in options.roles" :key="role.code" :value="role.code"
                         :label="`${role.name}（${role.code}）`" />
            </el-select>
            <el-select v-else-if="form.startScopeType === 'USER'" v-model="scopeValues" multiple filterable
                       placeholder="选择人员" style="width: 100%">
              <el-option v-for="user in users" :key="user.userId" :value="user.userId" :label="labelOf(user)" />
            </el-select>
            <el-select v-else v-model="scopeValues" multiple filterable placeholder="选择组织" style="width: 100%">
              <el-option v-for="org in options.orgs" :key="org.id" :value="String(org.id)" :label="org.name" />
            </el-select>
            <p class="hint" style="margin: 6px 0 0">
              不在范围内的用户登录后看不到这个流程，也无法通过接口发起；管理员不受限制。
            </p>
          </div>
        </el-form-item>

        <el-form-item label="上架状态">
          <el-switch v-model="form.enabled" active-text="可发起" inactive-text="停用" />
          <span class="hint" style="margin-left: 12px">停用后所有非管理员都无法发起，已在途实例不受影响</span>
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'
import { useUserDirectory } from '../composables/useUserDirectory'

const props = defineProps<{ modelValue: boolean; processKey: string; processName?: string }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const { users, ensureLoaded, labelOf } = useUserDirectory()

const CATEGORY_PRESETS = ['行政办公', '财务报销', '人事管理', '采购管理', '通用']
const ICON_PRESETS = ['🌴', '🧾', '📄', '🛒', '🚗', '🏢', '💼', '🔧', '📦', '✈️', '🎓', '💰']

const loading = ref(false)
const saving = ref(false)
const options = ref<{ roles: any[]; orgs: any[]; forms: any[] }>({ roles: [], orgs: [], forms: [] })
const scopeValues = ref<string[]>([])
const form = reactive({
  category: '通用', description: '', icon: '📄',
  startScopeType: 'ALL', baseFormCodes: [] as string[], enabled: true, sortOrder: 0
})

function onScopeTypeChange() { scopeValues.value = [] }

async function load() {
  if (!props.processKey) return
  loading.value = true
  ensureLoaded()
  try {
    const [settingsRes, optionsRes] = await Promise.all([
      http.get('/api/workflow/process-settings'),
      http.get('/api/workflow/node-configs/options')
    ])
    options.value = {
      roles: optionsRes.data.roles || [],
      orgs: optionsRes.data.orgs || [],
      forms: optionsRes.data.forms || []
    }
    const existing = (settingsRes.data || []).find((item: any) => item.processKey === props.processKey)
    form.category = existing?.category || '通用'
    form.description = existing?.description || ''
    form.icon = existing?.icon || '📄'
    form.startScopeType = existing?.startScopeType || 'ALL'
    form.baseFormCodes = Array.isArray(existing?.baseFormCodes) ? [...existing.baseFormCodes] : []
    form.enabled = existing ? !!existing.enabled : true
    form.sortOrder = existing?.sortOrder ?? 0
    scopeValues.value = existing?.startScopeValue
      ? String(existing.startScopeValue).split(',').map((v: string) => v.trim()).filter(Boolean)
      : []
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { loading.value = false }
}

watch(() => [props.modelValue, props.processKey], ([open]) => { if (open) load() })

async function save() {
  if (form.startScopeType !== 'ALL' && !scopeValues.value.length) {
    ElMessage.warning('请至少选择一个角色 / 人员 / 组织，或改为「所有人」')
    return
  }
  saving.value = true
  try {
    await http.put(`/api/workflow/process-settings/${props.processKey}`, {
      category: form.category,
      description: form.description,
      icon: form.icon,
      startScopeType: form.startScopeType,
      startScopeValue: form.startScopeType === 'ALL' ? '' : scopeValues.value.join(','),
      baseFormCodes: [...form.baseFormCodes],
      enabled: form.enabled,
      sortOrder: form.sortOrder
    })
    ElMessage.success('发起配置已保存，用户端目录立即生效')
    emit('saved')
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally { saving.value = false }
}
</script>

<style scoped>
.setting-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.icon-picker { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.icon-cell {
  width: 38px; height: 38px; border-radius: 10px; border: 1px solid var(--bp-line);
  background: #fff; font-size: 19px; cursor: pointer; transition: all .12s ease;
}
.icon-cell:hover { border-color: #A9C8BC; transform: translateY(-1px); }
.icon-cell.active { border-color: var(--el-color-primary); background: #EAF2EF; box-shadow: 0 0 0 2px rgba(23,86,74,.12); }
.scope-values { margin-top: 10px; width: 100%; }
</style>