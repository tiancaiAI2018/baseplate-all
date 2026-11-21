# CONSOLE COMPONENTS

## OVERVIEW
跨页面复用组件：流程办理/进度/实例详情/节点设置，以及 `agent/`（智能体助手）和 `cend/`（C 端页面设计器/渲染器）两个子域。

## STRUCTURE
```
components/
├── TaskHandleDrawer.vue / InstanceDetailDrawer.vue / InstanceProgress.vue / ProcessSettingDialog.vue
├── agent/   # 助手、消息块、BPMN 卡片、Markdown、Schema 字段编辑器、规则构造器
└── cend/    # BlockPalette、CendBlockNode、Inspector、PageRenderer、cendDsl
```

## CONVENTIONS
- 组件只接收 props / emit，不直接改路由或全局状态（store 操作放页面或 composable）。
- 组件内请求也走 `src/api/`，不直接 `axios`。
- 大对象（bpmn-js 实例、编辑器实例）用 `shallowRef`。
- 子域组件优先使用各自目录的约定，详见 `agent/AGENTS.md`、`cend/AGENTS.md`。

## ANTI-PATTERNS
- 在通用组件里写某个页面的业务。
- `catch` 静默吞异常导致界面空数据。
