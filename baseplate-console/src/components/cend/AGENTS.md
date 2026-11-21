# CEND COMPONENTS (C 端页面设计器)

## OVERVIEW
C 端活动页的块调色板、画布节点、属性检查器与独立渲染器；DSL 白名单与校验集中在 `cendDsl.ts`。

## FILES
| File | Purpose |
|------|---------|
| `cendDsl.ts` | `BLOCK_TYPES`、`ANIMATIONS`、样式键白名单、`registerCendBlock` |
| `BlockPalette.vue` | 左侧块列表/拖拽源 |
| `CendBlockNode.vue` | 画布节点渲染与选中 |
| `Inspector.vue` | 右侧属性编辑 |
| `PageRenderer.vue` | 运行/预览渲染 |

## CONVENTIONS
- 新增块类型：先加 `BLOCK_TYPES`（或 `registerCendBlock`），再补渲染与 Inspector 字段。
- 样式只允许白名单键（color/background/fontSize/padding/...）；不要透传任意 CSS。
- DSL 类型定义在 `src/types/cend.ts`，与 `cendDsl.ts` 白名单保持一致。
- 渲染端统一走 `PageRenderer`，预览与 `/c/:code` 行为一致。

## ANTI-PATTERNS
- 允许任意 HTML/脚本进入渲染（需要清洗）。
- 在块定义里写死业务接口。
- 白名单与类型定义不同步。
