# UTILS

## OVERVIEW
BPMN 相关纯函数：自动排版与规格/类型处理。

## FILES
| File | Purpose |
|------|---------|
| `bpmnLayout.ts` | `@dagrejs/dagre` LR 分层排版，改 DI 坐标、不动业务元素 |
| `bpmnSpec.ts` | BPMN 元素类型/规格辅助 |

## CONVENTIONS
- 排版只修改 `BPMNDiagram` 坐标，不改变流程语义。
- 回退边不参与分层，从节点下方绕行；默认横向（LR），不要纵向单列。
- bpmn 元素类型带命名空间（`bpmn:UserTask`），处理前去掉前缀并转小写。
- 纯函数、可单测；不引入组件或 store。

## ANTI-PATTERNS
- 用 `bpmn-auto-layout` 默认纵向排版。
- 在排版里改节点业务属性。
- 直接改库里的部署 XML（返回前端时补 DI 即可）。
