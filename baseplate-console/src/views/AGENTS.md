# CONSOLE VIEWS

## OVERVIEW
25 个页面，按路由分区：概览、identity、platform、audit、rule、workflow、cend、agent、登录/改密/授权。

## GROUPS
| Area | Views |
|------|-------|
| 概览 | `DashboardView` |
| identity | `UsersView` `RolesView` `OrgsView` |
| platform | `OpenAppsView` `ApiCatalogView` `OAuthAuthorizeView` |
| audit / rule | `AuditView` `RulesView` |
| workflow | `ProcessStartView` `MyTodoView` `MyInstancesView` `WorkflowView` `FormListView` `FormDesignerView` `WorkflowLabView` `NodeConfigPanel` |
| cend | `CendPageListView` `CendPageDesignerView` `CendRendererView` |
| agent | `AgentConfigView` `AgentObserveView` |
| auth | `LoginView` `ChangePasswordView` |

## CONVENTIONS
- 页面通过 `pageKey` 绑定智能体助手；工具作用域按页面 key 过滤，写错 key 模型看不到工具。
- 数据块独立请求、独立渲染；首页用 `/overview`，不要拉明细接口。
- 权限判断用 `auth.has('code')`；菜单可见性由模块清单 + 权限决定。
- 表单设计器/画布类页面：bpmn-js 用 `shallowRef`，选中联动先比较 id 防死循环。
- 每个页面新增后必须逐页渲染冒烟，不能只看 build 成功。

## ANTI-PATTERNS
- 页面内直接 `axios`。
- 首屏 `Promise.allSettled` 后统一赋值。
- 双向联动不加相等判断导致栈溢出。
