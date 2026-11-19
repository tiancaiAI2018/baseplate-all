<template>
  <div class="agent-markdown" v-html="html"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const props = defineProps<{ text: string }>()

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, language) {
    if (language && hljs.getLanguage(language)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language }).value}</code></pre>`
      } catch {
        // 语言解析失败时退回纯文本
      }
    }
    return `<pre class="hljs"><code>${escapeHtml(code)}</code></pre>`
  }
})

/** 模型输出不可信：v-html 前统一过 DOMPurify，禁止脚本与内联事件。 */
const html = computed(() => DOMPurify.sanitize(md.render(props.text || '')))
</script>

<style scoped>
.agent-markdown {
  font-size: 13px;
  line-height: 1.7;
  color: var(--bp-ink);
  word-break: break-word;
}
.agent-markdown :deep(p) {
  margin: 0 0 8px;
}
.agent-markdown :deep(p:last-child) {
  margin-bottom: 0;
}
.agent-markdown :deep(code) {
  background: #F1F4F2;
  border-radius: 4px;
  padding: 1px 4px;
  font-size: 12px;
}
.agent-markdown :deep(pre) {
  background: #F6F8F7;
  border: 1px solid var(--bp-line);
  border-radius: 6px;
  padding: 10px;
  overflow-x: auto;
}
.agent-markdown :deep(pre code) {
  background: transparent;
  padding: 0;
}
.agent-markdown :deep(ul),
.agent-markdown :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}
.agent-markdown :deep(a) {
  color: var(--bp-accent);
}
.agent-markdown :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 8px 0;
}
.agent-markdown :deep(th),
.agent-markdown :deep(td) {
  border: 1px solid var(--bp-line);
  padding: 4px 8px;
  text-align: left;
}
</style>
