# WORKFLOW STARTER

## OVERVIEW
Flowable 8 封装：流程定义/部署、草稿编辑、节点配置编译、发起/待办/办理/驳回/迁移、表单绑定、流程演练、BPMN 校验与补图。

## STRUCTURE
```
com.example.baseplate.workflow/
├── adapter/in/web/WorkflowController、WorkflowDraftController
├── adapter/out/flowable/  # FlowableProcessEngineAdapter、BpmnXmlValidator、BpmnDiagramEnricher、
│                          # NodeConfigCompiler、AssigneeResolverListener、WorkflowOperationLogger、
│                          # UnavailableProcessEnginePort
├── adapter/out/persistence/WorkflowConfigRepository、WorkflowDraftRepository
├── application/  # WorkflowDraftService、BpmnDraftComposer、AssigneeResolver、FieldPolicyService、
│                 # RejectRouteSupport、WorkflowAccessService、WorkflowConditionValidator、WorkflowLabInitializer
└── autoconfigure/WorkflowAutoConfiguration.java
resources/db/migration/workflow/  # operation_log、platform、widget_seed、process_setting、draft、multi_form
resources/META-INF/baseplate-module.yml   # wf:* 权限
```

## FLOWABLE RULES
- 部署必须 `.disableSchemaValidation()`，否则 fat jar 下 XSD 解析失败；XML 安全由 `BpmnXmlValidator` 白名单保证。
- 已结束实例的监控/详情/轨迹走 `HistoryService`；`TaskService` 只反映运行中。
- 驳回分支必须由 BPMN 排他网关消费 `decision` 变量，否则通过/驳回走同一结束点。
- 流程变量办理人 ID 必须与 `taskCandidateOrAssigned` 使用同一标识（`userId`，不要混 `username`）。
- 作废定义：`suspendProcessDefinitionById(id, false, null)`，`false` = 不挂起已有实例。
- 版本迁移先按 `BpmnModel` 预检目标节点集合，任一不兼容整体拒绝。
- 存量无 DI 的 BPMN 由 `BpmnDiagramEnricher` 在返回前端时补坐标，不改库里部署内容。
- 条件只接受 `${...}` JUEL；带 `language=` 的脚本表达式被拒绝。

## ANTI-PATTERNS
- 启动脚本漏 `classpath:db/migration/workflow`（表不建但流程照跑，操作日志静默缺失）。
- 用运行表统计已结束实例。
- 按「最近一条有 reason 的操作」当驳回原因（会被后续同意覆盖）。
- 迁移到一半才发现节点对不上。

## TESTS
`src/test/.../NodeConfigCompilerRejectRouteTest`、`RejectRouteSupportTest`、`WorkflowConditionValidatorTest`、`FieldPolicyServiceTest`、`FlowableRejectAttributeProbeTest`。
