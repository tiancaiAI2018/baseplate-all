<template>
  <div class="page">
    <h2 class="page-title">组织架构</h2>
    <p class="page-desc">部门树；角色数据范围按组织联动。删除前必须确保没有下级。</p>

    <div class="toolbar">
      <el-button type="primary" @click="openCreate(null)">新建根组织</el-button>
    </div>

    <div class="card-block">
      <el-table :data="tree" row-key="id" :tree-props="{ children: 'children' }" size="small">
        <el-table-column prop="name" label="组织名称" />
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="path" label="路径" width="220" />
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button size="small" @click="openCreate(row.id)">新增下级</el-button>
            <el-button size="small" @click="rename(row)">重命名</el-button>
            <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { errorText } from '../api/client'

const orgs = ref<any[]>([])
const tree = computed(() => {
  const map = new Map<number, any>()
  orgs.value.forEach((org) => map.set(org.id, { ...org, children: [] }))
  const roots: any[] = []
  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) {
      map.get(node.parentId).children.push(node)
    } else {
      roots.push(node)
    }
  })
  return roots
})

async function load() {
  try {
    const { data } = await http.get('/api/identity/orgs')
    orgs.value = data
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function openCreate(parentId: number | null) {
  try {
    const { value } = await ElMessageBox.prompt('请输入组织名称', parentId ? '新增下级组织' : '新建根组织')
    await http.post('/api/identity/orgs', { parentId, name: value })
    ElMessage.success('已创建')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function rename(row: any) {
  try {
    const { value } = await ElMessageBox.prompt('新的组织名称', '重命名', { inputValue: row.name })
    await http.put(`/api/identity/orgs/${row.id}`, { parentId: row.parentId, name: value })
    ElMessage.success('已保存')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除组织 ${row.name}？`, '删除组织', { type: 'warning' })
    await http.delete(`/api/identity/orgs/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(errorText(error))
  }
}

onMounted(load)
</script>
