<template>
  <div class="page">
    <h2 class="page-title">低代码配置</h2>
    <p class="page-desc">
      表单 / 列表 / 流程三类配置统一使用 Schema + UISchema。只存定义，不触发运行时 DDL。
    </p>

    <div class="card-block">
      <div class="toolbar">
        <el-select v-model="kind" style="width: 150px" @change="loadList">
          <el-option label="表单 FORM" value="FORM" />
          <el-option label="列表 LIST" value="LIST" />
          <el-option label="流程 FLOW" value="FLOW" />
        </el-select>
        <el-button @click="loadList">刷新</el-button>
      </div>
      <el-table :data="list" size="small">
        <el-table-column prop="code" label="编码" width="180" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button size="small" @click="loadOne(row)">载入</el-button>
            <el-button size="small" @click="preview(row)">预览</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card-block" style="margin-top: 16px">
      <el-form label-width="90px">
        <el-form-item label="编码"><el-input v-model="code" placeholder="如 leave_form" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="name" /></el-form-item>
        <el-form-item label="Schema">
          <el-input v-model="schema" type="textarea" :rows="10" class="mono" />
        </el-form-item>
        <el-form-item label="UISchema">
          <el-input v-model="uiSchema" type="textarea" :rows="5" class="mono" />
        </el-form-item>
      </el-form>
      <div class="toolbar">
        <el-button type="primary" @click="save">保存草稿</el-button>
        <el-input-number v-model="publishVersion" :min="1" />
        <el-button @click="publish">发布该版本</el-button>
      </div>
      <p class="hint">Schema 必须包含 fields 数组；字段类型限定在白名单内，禁止 script/expression。</p>
    </div>

    <el-dialog v-model="previewOpen" title="渲染结果" width="680px">
      <pre class="mono" style="white-space: pre-wrap">{{ JSON.stringify(previewData, null, 2) }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { errorText } from '../api/client'

const kind = ref('FORM')
const list = ref<any[]>([])
const code = ref('leave_form')
const name = ref('请假申请')
const publishVersion = ref(1)
const previewOpen = ref(false)
const previewData = ref<any>(null)
const schema = ref(JSON.stringify({
  fields: [
    { name: 'title', label: '标题', type: 'STRING', required: true },
    { name: 'days', label: '天数', type: 'NUMBER', required: true },
    { name: 'reason', label: '事由', type: 'TEXT' }
  ]
}, null, 2))
const uiSchema = ref(JSON.stringify({ layout: 'vertical', columns: 1 }, null, 2))

async function loadList() {
  try {
    const { data } = await http.get(`/api/metadata/${kind.value}`)
    list.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function loadOne(row: any) {
  code.value = row.code
  name.value = row.name
  publishVersion.value = row.version
  schema.value = row.schemaJson
  uiSchema.value = row.uiSchemaJson
}

async function save() {
  try {
    const { data } = await http.post(`/api/metadata/${kind.value}/${code.value}`, {
      name: name.value, schema: schema.value, uiSchema: uiSchema.value
    })
    publishVersion.value = data.version
    ElMessage.success('草稿已保存，版本 ' + data.version)
    await loadList()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function publish() {
  try {
    await http.post(`/api/metadata/${kind.value}/${code.value}/publish`, null, {
      params: { version: publishVersion.value }
    })
    ElMessage.success('已发布')
    await loadList()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function preview(row: any) {
  try {
    const { data } = await http.get(`/api/metadata/${kind.value}/${row.code}/render`)
    previewData.value = data
    previewOpen.value = true
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(loadList)
</script>
