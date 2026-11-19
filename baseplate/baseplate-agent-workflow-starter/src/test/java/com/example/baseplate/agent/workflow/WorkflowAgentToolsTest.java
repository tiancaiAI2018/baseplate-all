package com.example.baseplate.agent.workflow;

import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.model.DataScope;
import com.example.baseplate.api.model.PageResult;
import com.example.baseplate.api.model.ProcessInstanceView;
import com.example.baseplate.api.model.WorkflowDoneTaskView;
import com.example.baseplate.api.model.WorkflowInstanceDetail;
import com.example.baseplate.api.model.WorkflowInstancePage;
import com.example.baseplate.api.model.WorkflowTaskTrace;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.workflow.application.WorkflowAccessService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人流程工具的回归探针。
 *
 * <p>这些方法只做「收窄查询条件 + 组装卡片」，用动态代理假引擎就能覆盖，
 * 不需要起 Spring 或连数据库。重点盯三件事：
 * 我发起的必须强制只看自己、业务单号多义不能瞎猜、进度节点名要能翻译回中文。</p>
 */
class WorkflowAgentToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CurrentUser USER = new CurrentUser("u1", "default", "张三",
            Set.of("EMPLOYEE"), Set.of("wf:task"), false);

    /** 引擎调用记录：参数原样留着，断言查询条件有没有被收窄。 */
    private record Call(String name, Object[] args) {
    }

    @Test
    void listMyStartedDefaultsToRunningAndScopesToCurrentUser() throws Exception {
        List<Call> calls = new ArrayList<>();
        ProcessEnginePort engine = engine(call -> {
            if ("instancePage".equals(call.name())) {
                return new WorkflowInstancePage(List.of(instance("i1", "BIZ-1", false,
                        List.of("部门经理审批"))), 7);
            }
            return null;
        }, calls);

        Map<?, ?> result = read(tools(engine).listMyStartedFor(USER, null, null, null, null));

        assertEquals(7, ((Number) result.get("total")).intValue());
        assertEquals("RUNNING", calls.get(0).args()[2]);
        assertEquals(Set.of("u1"), calls.get(0).args()[5], "必须强制只看自己发起的");

        Map<?, ?> ui = (Map<?, ?>) result.get("ui");
        assertEquals("instance_list", ui.get("kind"));
        assertTrue(String.valueOf(ui.get("title")).contains("7"));
        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("i1", ((Map<?, ?>) items.get(0)).get("instanceId"));
    }

    @Test
    void listMyStartedAcceptsStateAliases() {
        assertEquals("FINISHED", WorkflowAgentTools.normalizeState("done"));
        assertEquals("FINISHED", WorkflowAgentTools.normalizeState("ENDED"));
        assertEquals("ALL", WorkflowAgentTools.normalizeState("all"));
        assertEquals("RUNNING", WorkflowAgentTools.normalizeState(null));
        assertEquals("RUNNING", WorkflowAgentTools.normalizeState("随便写的"));
    }

    @Test
    void instanceProgressTranslatesActivityIdsToNodeNames() throws Exception {
        ProcessEnginePort engine = engine(call -> switch (call.name()) {
            case "instanceDetail" -> detail("i1", "u1", false,
                    List.of("task2"), List.of("task1"), List.of());
            default -> null;
        });

        Map<?, ?> result = read(tools(engine).instanceProgressFor(USER, "i1", null));

        assertEquals(List.of("部门经理审批"), result.get("currentNodes"));
        assertEquals(List.of("发起申请"), result.get("completedNodes"));
        assertEquals("运行中", result.get("status"));

        Map<?, ?> ui = (Map<?, ?>) result.get("ui");
        assertEquals("instance_progress", ui.get("kind"));
        assertEquals("i1", ui.get("instanceId"));
        assertTrue(String.valueOf(result.get("summary")).contains("停在：部门经理审批"));
    }

    @Test
    void instanceProgressByBusinessKeyReturnsCandidatesInsteadOfGuessing() throws Exception {
        boolean[] detailCalled = {false};
        ProcessEnginePort engine = engine(call -> switch (call.name()) {
            case "instancePage" -> new WorkflowInstancePage(List.of(
                    instance("i1", "BIZ-1", false, List.of("审批中")),
                    instance("i2", "BIZ-1", false, List.of("审批中"))), 2);
            case "instanceDetail" -> {
                detailCalled[0] = true;
                yield null;
            }
            default -> null;
        });

        Map<?, ?> result = read(tools(engine).instanceProgressFor(USER, null, "BIZ-1"));

        assertEquals(Boolean.TRUE, result.get("ambiguous"));
        assertFalse(detailCalled[0], "多义时不能再按第一个继续查进度");
        assertEquals(2, ((List<?>) result.get("items")).size());
        assertEquals("instance_list", ((Map<?, ?>) result.get("ui")).get("kind"));
    }

    @Test
    void instanceProgressRejectsUnknownBusinessKey() {
        ProcessEnginePort engine = engine(call -> "instancePage".equals(call.name())
                ? new WorkflowInstancePage(List.of(), 0) : null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tools(engine).instanceProgressFor(USER, null, "BIZ-NOT-EXIST"));
        assertTrue(error.getMessage().contains("没找到业务单号"));
    }

    @Test
    void instanceProgressRejectsInstanceOutsideDataScope() {
        ProcessEnginePort engine = engine(call -> switch (call.name()) {
            case "instanceDetail" -> detail("i9", "u2", false, List.of("task1"), List.of(), List.of());
            case "isParticipant" -> false;
            default -> null;
        });
        // SELF 范围：只能看自己发起的；i9 是 u2 发的，且我不是参与人
        WorkflowAccessService access = new WorkflowAccessService(null,
                (user, aggregate) -> new DataScope(DataScope.ScopeKind.SELF,
                        Set.of(), Set.of(), "default"), engine);
        WorkflowAgentTools tools = new WorkflowAgentTools(null, engine, access);

        assertThrows(ForbiddenException.class,
                () -> tools.instanceProgressFor(USER, "i9", null));
    }

    @Test
    void mySummaryCountsFourNumbers() throws Exception {
        ProcessEnginePort engine = engine(call -> switch (call.name()) {
            case "taskCount" -> 3L;
            case "instancePage" -> {
                // 参数依次是 page/size/state/processKey/keyword/startUserIds
                yield new WorkflowInstancePage(List.of(),
                        "RUNNING".equals(call.args()[2]) ? 2 : 4);
            }
            case "done" -> new PageResult<>(List.of(), 5);
            default -> null;
        });

        Map<?, ?> result = read(tools(engine).mySummaryFor(USER));

        List<?> stats = (List<?>) result.get("stats");
        assertEquals(4, stats.size());
        assertEquals(3, ((Number) ((Map<?, ?>) stats.get(0)).get("value")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) stats.get(1)).get("value")).intValue());
        assertEquals(4, ((Number) ((Map<?, ?>) stats.get(2)).get("value")).intValue());
        assertEquals(5, ((Number) ((Map<?, ?>) stats.get(3)).get("value")).intValue());
        assertEquals("stat_group", ((Map<?, ?>) result.get("ui")).get("kind"));
    }

    @Test
    void listMyDoneShowsDecisionText() throws Exception {
        WorkflowDoneTaskView view = new WorkflowDoneTaskView("t1", "部门经理审批", "i1",
                "leave", "请假审批", "BIZ-1", "task2", "u1", "张三",
                Instant.parse("2026-11-18T02:00:00Z"), Instant.parse("2026-11-18T03:00:00Z"),
                3_600_000L, "APPROVE", "同意");
        ProcessEnginePort engine = engine(call -> "done".equals(call.name())
                ? new PageResult<>(List.of(view), 1) : null);

        Map<?, ?> result = read(tools(engine).listMyDoneFor(USER, null, null, null));

        List<?> items = (List<?>) result.get("items");
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("i1", item.get("instanceId"));
        assertTrue(String.valueOf(item.get("subtitle")).contains("已同意"));
        assertEquals("instance_list", ((Map<?, ?>) result.get("ui")).get("kind"));
    }

    // ==================== 测试替身 ====================

    private static WorkflowAgentTools tools(ProcessEnginePort engine) {
        // 数据范围返回 null = ALL，先让可见性校验通过；拒绝场景在用例里单独构造
        WorkflowAccessService access = new WorkflowAccessService(null,
                (user, aggregate) -> null, engine);
        return new WorkflowAgentTools(null, engine, access);
    }

    private static Map<?, ?> read(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    private static ProcessInstanceView instance(String id, String businessKey,
                                                boolean ended, List<String> currentTasks) {
        return new ProcessInstanceView(id, "leave", "请假审批", businessKey, "u1",
                Instant.parse("2026-11-18T01:00:00Z"),
                ended ? Instant.parse("2026-11-18T04:00:00Z") : null,
                ended, currentTasks);
    }

    private static WorkflowInstanceDetail detail(String id, String startUserId, boolean ended,
                                                 List<String> current,
                                                 List<String> completed,
                                                 List<String> rolledBack) {
        List<WorkflowTaskTrace> traces = List.of(
                new WorkflowTaskTrace("t1", "发起申请", startUserId, "task1",
                        Instant.parse("2026-11-18T01:00:00Z"),
                        Instant.parse("2026-11-18T01:10:00Z"), 600_000L,
                        "APPROVE", "", null, true),
                new WorkflowTaskTrace("t2", "部门经理审批", "u2", "task2",
                        Instant.parse("2026-11-18T01:10:00Z"), null, null,
                        null, "", null, false));
        return new WorkflowInstanceDetail(id, "leave:1:1", "leave", "请假审批", 1,
                "BIZ-1", startUserId, Instant.parse("2026-11-18T01:00:00Z"),
                ended ? Instant.parse("2026-11-18T04:00:00Z") : null, ended, null,
                ended ? 10_800_000L : null, current, completed, rolledBack, 0,
                "", "", traces, Map.of(), List.of());
    }

    private interface Stub {
        Object call(Call call);
    }

    private static ProcessEnginePort engine(Stub stub) {
        return engine(stub, new ArrayList<>());
    }

    private static ProcessEnginePort engine(Stub stub, List<Call> calls) {
        return (ProcessEnginePort) Proxy.newProxyInstance(
                ProcessEnginePort.class.getClassLoader(),
                new Class<?>[]{ProcessEnginePort.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "stub-engine";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Call call = new Call(method.getName(), args == null ? new Object[0] : args);
                    calls.add(call);
                    Object result = stub.call(call);
                    if (result == null && method.getReturnType().isPrimitive()) {
                        return primitiveDefault(method.getReturnType());
                    }
                    return result;
                });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
