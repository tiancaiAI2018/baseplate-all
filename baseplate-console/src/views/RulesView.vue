<template>
  <div class="page">
    <h2 class="page-title">规则配置</h2>
    <p class="page-desc">只用下拉选择字段、运算符与值，不写表达式文本，因此没有表达式注入面。</p>

    <div class="toolbar">
      <el-input v-model="scope" placeholder="规则作用域，如 order-discount" style="width: 280px" />
      <el-button @click="loadRule">加载</el-button>
      <el-button type="primary" @click="save">保存新版本</el-button>
    </div>

    <div class="card-block">
      <el-form label-width="90px">
        <el-form-item label="匹配方式">
          <el-radio-group v-model="matchMode">
            <el-radio value="ALL">全部条件满足</el-radio>
            <el-radio value="ANY">任一条件满足</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="条件">
          <div style="width: 100%">
            <div v-for="(condition, index) in conditions" :key="index" class="toolbar" style="margin-bottom: 8px">
              <el-select v-model="condition.field" placeholder="字段" style="width: 190px" @change="onFieldChange(condition)">
                <el-option v-for="field in fields" :key="field.name" :label="field.label + ' (' + field.name + ')'" :value="field.name" />
              </el-select>
              <el-select v-model="condition.op" placeholder="运算符" style="width: 130px">
                <el-option v-for="op in ops" :key="op.value" :label="op.label" :value="op.value" />
              </el-select>
              <el-select
                v-if="optionsOf(condition.field).length"
                v-model="condition.value"
                placeholder="值"
                style="width: 180px"
              >
                <el-option v-for="option in optionsOf(condition.field)" :key="option" :label="option" :value="option" />
              </el-select>
              <el-input v-else v-model="condition.value" placeholder="值" style="width: 180px" />
              <el-button text type="danger" @click="conditions.splice(index, 1)">删除</el-button>
            </div>
            <el-button size="small" @click="addCondition">添加条件</el-button>
          </div>
        </el-form-item>
        <el-form-item label="命中动作">
          <el-input v-model="actionsText" placeholder="逗号分隔，如: DISCOUNT_10, NOTIFY_MANAGER" />
        </el-form-item>
        <el-button type="primary" @click="save">保存新版本</el-button>
      </el-form>
    </div>

    <div class="card-block" style="margin-top: 16px">
      <h3 style="margin-top: 0">试算</h3>
      <el-input v-model="contextText" type="textarea" :rows="5" placeholder='{"amount": 1200, "userLevel": "VIP"}' />
      <div class="toolbar" style="margin-top: 10px">
        <el-checkbox v-model="dryRun">Dry-run</el-checkbox>
        <el-button type="primary" plain @click="evaluate">执行</el-button>
      </div>
      <pre v-if="result" class="mono" style="white-space: pre-wrap">{{ JSON.stringify(result, null, 2) }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'

const scope = ref('order-discount')
const fields = ref<any[]>([])
const conditions = ref<any[]>([])
const matchMode = ref('ALL')
const actionsText = ref('')
const contextText = ref('{\n  "amount": 1200,\n  "userLevel": "VIP"\n}')
const dryRun = ref(true)
const result = ref<any>(null)
const ops = [
  { label: '等于', value: 'EQ' },
  { label: '不等于', value: 'NE' },
  { label: '大于', value: 'GT' },
  { label: '大于等于', value: 'GTE' },
  { label: '小于', value: 'LT' },
  { label: '小于等于', value: 'LTE' },
  { label: '属于', value: 'IN' },
  { label: '包含', value: 'CONTAINS' }
]

function addCondition() {
  conditions.value.push({ field: '', op: 'EQ', value: '' })
}

function optionsOf(fieldName: string): string[] {
  return fields.value.find((field) => field.name === fieldName)?.options || []
}

function onFieldChange(condition: any) {
  condition.value = ''
}

async function loadFields() {
  try {
    const { data } = await http.get('/api/rules/fields')
    fields.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function loadRule() {
  try {
    const { data } = await http.get(`/api/rules/${encodeURIComponent(scope.value)}`)
    matchMode.value = data.matchMode
    conditions.value = (data.conditions || []).map((item: any) => ({ ...item }))
    actionsText.value = (data.actions || []).join(', ')
  } catch (error: any) {
    ElMessage.warning(error?.response?.status === 400 ? '该作用域还没有规则，可直接保存创建' : errorText(error))
  }
}

async function save() {
  try {
    const payload = {
      matchMode: matchMode.value,
      conditions: conditions.value,
      actions: actionsText.value.split(',').map((item) => item.trim()).filter(Boolean)
    }
    await http.put(`/api/rules/${encodeURIComponent(scope.value)}`, payload)
    ElMessage.success('已保存新版本')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function evaluate() {
  try {
    const context = JSON.parse(contextText.value || '{}')
    const { data } = await http.post(`/api/rules/${encodeURIComponent(scope.value)}/evaluate`, {
      context, dryRun: dryRun.value
    })
    result.value = data
  } catch (error: any) {
    ElMessage.error(error instanceof SyntaxError ? '上下文不是合法 JSON' : errorText(error))
  }
}

onMounted(loadFields)
</script>
