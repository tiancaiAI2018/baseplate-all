<template>
  <div class="schema-fields">
    <div v-for="(node, index) in modelValue" :key="index" class="schema-node">
      <div class="schema-row">
        <el-input v-model="node.name" placeholder="参数名" style="width: 140px" />
        <el-select v-model="node.type" style="width: 100px" @change="onTypeChange(node)">
          <el-option v-for="type in SCHEMA_TYPES" :key="type" :label="type" :value="type" />
        </el-select>
        <template v-if="node.type === 'array'">
          <span class="schema-muted">元素</span>
          <el-select v-model="node.itemType" style="width: 100px" @change="onItemTypeChange(node)">
            <el-option v-for="type in SCHEMA_TYPES" :key="type" :label="type" :value="type" />
          </el-select>
        </template>
        <el-checkbox v-model="node.required">必填</el-checkbox>
        <el-input v-model="node.description" placeholder="说明" style="width: 150px" />
        <el-button link type="danger" @click="remove(index)">删</el-button>
      </div>

      <!-- object（或元素为 object 的 array）展开子字段，层级由此递归 -->
      <div v-if="hasChildren(node)" class="schema-children">
        <SchemaFieldEditor v-model="node.children" :depth="(depth || 0) + 1" />
        <el-button size="small" style="width: 100%" @click="addChild(node)">添加子字段</el-button>
      </div>
    </div>
    <el-button v-if="!depth" size="small" style="width: 100%" @click="add()">添加参数</el-button>
  </div>
</template>

<script setup lang="ts">
import { SCHEMA_TYPES, emptyNode, type SchemaNode } from './schemaField'

/** 递归组件：object / object-array 的子字段继续用同一个编辑器渲染。 */
defineOptions({ name: 'SchemaFieldEditor' })

const props = defineProps<{
  modelValue: SchemaNode[]
  depth?: number
}>()

function hasChildren(node: SchemaNode) {
  return node.type === 'object' || (node.type === 'array' && node.itemType === 'object')
}

function onTypeChange(node: SchemaNode) {
  if (node.type === 'object' || (node.type === 'array' && node.itemType === 'object')) {
    node.children = node.children || []
  }
}

function onItemTypeChange(node: SchemaNode) {
  if (node.itemType === 'object') {
    node.children = node.children || []
  }
}

function add() {
  props.modelValue.push(emptyNode())
}

function addChild(node: SchemaNode) {
  node.children = node.children || []
  node.children.push(emptyNode())
}

function remove(index: number) {
  props.modelValue.splice(index, 1)
}
</script>

<style scoped>
.schema-node {
  margin-bottom: 6px;
}
.schema-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.schema-children {
  margin: 4px 0 8px 18px;
  padding-left: 10px;
  border-left: 1px dashed var(--bp-line);
}
.schema-muted {
  font-size: 11px;
  color: var(--bp-muted);
}
</style>
