<template>
  <div class="blocks-view">
    <template v-for="(block, index) in blocks" :key="index">
      <div v-if="block.type === 'text'" class="text-block"><AgentMarkdown :text="block.text || ''" /></div>
      <el-collapse v-else-if="block.type === 'thinking'" class="thinking">
        <el-collapse-item title="思考过程" :name="String(index)">
          <div class="thinking-text">{{ block.text }}</div>
        </el-collapse-item>
      </el-collapse>
      <div v-else-if="block.type === 'tool_call'" class="chip">
        <el-icon><Tools /></el-icon><span>调用工具：{{ block.tool }}</span>
      </div>
      <div v-else-if="block.type === 'tool_result'" class="chip" :class="block.ok ? 'is-ok' : 'is-fail'">
        <el-icon><CircleCheck v-if="block.ok" /><CircleClose v-else /></el-icon>
        <span>工具结果：{{ block.tool }}</span>
      </div>
      <div v-else-if="block.type === 'hitl'" class="hitl">
        <div class="hitl-head">
          人工确认（{{ block.toolCalls?.length || 0 }} 个调用）
          <el-tag size="small" effect="plain"
                  :type="block.status === 'APPROVED' ? 'success' : (block.status === 'REJECTED' ? 'danger' : 'warning')">
            {{ hitlStatusText(block.status) }}
          </el-tag>
        </div>
        <code v-for="call in block.toolCalls || []" :key="call.callId" class="hitl-args">
          {{ call.tool }} {{ formatArgs(call.args) }}
        </code>
      </div>
      <div v-else-if="block.type === 'external_tool_call'" class="hitl">
        <div class="hitl-head">
          页面操作：{{ block.tool }}
          <el-tag size="small" effect="plain"
                  :type="block.status === 'DONE' ? 'success' : (block.status === 'FAILED' ? 'danger' : 'info')">
            {{ block.status || '—' }}
          </el-tag>
        </div>
        <code class="hitl-args">{{ formatArgs(block.args) }}</code>
      </div>
      <el-tag v-else-if="block.type === 'skill'" size="small" type="success" effect="plain">
        使用技能：{{ block.name || block.tool }}
      </el-tag>
      <AgentBpmnCard v-else-if="block.type === 'ui' && block.kind === 'workflow_preview'"
                     :xml="block.bpmnXml || ''" :title="block.title" />
      <AgentChartCard v-else-if="block.type === 'ui' && block.kind === 'chart'" :block="block" />
      <AgentDataTableCard v-else-if="block.type === 'ui' && block.kind === 'table'" :block="block" />
      <div v-else-if="block.type === 'ui'" class="ui-card">
        <div class="ui-head">{{ block.title || block.label || '页面指令' }}
          <el-tag size="small" effect="plain">{{ block.kind || 'ui' }}</el-tag>
        </div>
        <pre class="ui-json">{{ uiSummary(block) }}</pre>
      </div>
      <el-alert v-else-if="block.type === 'error'" :title="block.message" type="error" :closable="false" />
    </template>
    <div v-if="!blocks?.length" class="empty">（这条消息没有内容块）</div>
  </div>
</template>

<script setup lang="ts">
// 观测页专用的只读块渲染：与悬浮助手的流式渲染同构，但去掉了
// 审批按钮 / 办理按钮等交互——管理员只看过程，不代替用户操作。
import { CircleCheck, CircleClose, Tools } from '@element-plus/icons-vue'
import AgentMarkdown from './AgentMarkdown.vue'
import AgentBpmnCard from './AgentBpmnCard.vue'
import AgentChartCard from './AgentChartCard.vue'
import AgentDataTableCard from './AgentDataTableCard.vue'

defineProps<{ blocks: Array<Record<string, any>> }>()

function formatArgs(args: unknown) {
  if (args == null) return ''
  const text = JSON.stringify(args)
  return text.length > 300 ? text.slice(0, 300) + '…' : text
}

/** ui 块只保留关键字段，避免整卡 JSON 刷屏。 */
function uiSummary(block: Record<string, any>) {
  const rest: Record<string, any> = { ...block }
  delete rest.type
  delete rest.kind
  delete rest.title
  delete rest.label
  delete rest.route
  const text = JSON.stringify(rest)
  return text === '{}' ? '（无附加数据）' : (text.length > 500 ? text.slice(0, 500) + '…' : text)
}

function hitlStatusText(status?: string) {
  if (status === 'APPROVED') return '已同意'
  if (status === 'REJECTED') return '已拒绝'
  return status || '待确认'
}
</script>

<style scoped>
.blocks-view {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.text-block {
  font-size: 13px;
  line-height: 1.6;
}
.thinking {
  border: 1px solid var(--bp-line);
  border-radius: 6px;
}
.thinking-text {
  font-size: 12px;
  color: var(--bp-muted);
  white-space: pre-wrap;
  word-break: break-word;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--bp-bg);
  border: 1px solid var(--bp-line);
  color: var(--bp-muted);
  width: fit-content;
}
.chip.is-ok { color: #2E7D46; border-color: #CDE7D6; background: #F2FAF4; }
.chip.is-fail { color: #B23B3B; border-color: #F0D2D2; background: #FDF3F3; }
.hitl {
  border: 1px solid #E3D6B8;
  background: #FDFBF4;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.hitl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #7A5C1E;
}
.hitl-args {
  display: block;
  background: #fff;
  border: 1px solid var(--bp-line);
  border-radius: 4px;
  padding: 4px 6px;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}
.ui-card {
  border: 1px solid var(--bp-line);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  background: var(--bp-card);
}
.ui-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 6px;
}
.ui-json {
  margin: 0;
  font-size: 11px;
  color: var(--bp-muted);
  white-space: pre-wrap;
  word-break: break-all;
}
.empty {
  font-size: 12px;
  color: var(--bp-muted);
}
</style>
