package com.example.baseplate.workflow.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 驳回路由回归探针：图上画的「后→前」连线必须能被识别、加守卫、按条件选目标，
 * 且配置写错时不能把驳回整个卡死。
 */
class RejectRouteSupportTest {

    @Test
    void rejectRouteFlagIsExplicitOnly() {
        assertTrue(RejectRouteSupport.isRejectRoute(Map.of("rejectRoute", true)));
        assertTrue(RejectRouteSupport.isRejectRoute(Map.of("rejectRoute", "TRUE")));
        assertFalse(RejectRouteSupport.isRejectRoute(Map.of("rejectRoute", false)));
        assertFalse(RejectRouteSupport.isRejectRoute(Map.of()));
        assertFalse(RejectRouteSupport.isRejectRoute(null));
        // 只配了普通 condition 的线绝不能被当成驳回路由
        assertFalse(RejectRouteSupport.isRejectRoute(Map.of("expression", "${days > 5}")));
    }

    @Test
    void guardedExpressionKeepsOriginalAndBlocksForwardFlow() {
        assertEquals("${decision == 'REJECT' && (days > 5)}",
                RejectRouteSupport.guardedExpression("${days > 5}"));
        assertEquals("${decision == 'REJECT' && (days > 5)}",
                RejectRouteSupport.guardedExpression("days > 5"));
        // 无条件驳回路由也必须带守卫，否则正常审批会沿它绕回去
        assertEquals("${decision == 'REJECT' && true}", RejectRouteSupport.guardedExpression(null));
    }

    @Test
    void compiledConditionIsTheRuntimeMarkerBecauseFlowableDropsCustomAttributes() {
        String compiled = RejectRouteSupport.guardedExpression("${days > 5}");
        assertTrue(RejectRouteSupport.isCompiledRejectRoute(compiled));
        assertEquals("${days > 5}", RejectRouteSupport.originalCondition(compiled));
        // 无条件的也能识别，且还原为 null（由 selectTarget 视为无条件命中）
        String unconditional = RejectRouteSupport.guardedExpression(null);
        assertTrue(RejectRouteSupport.isCompiledRejectRoute(unconditional));
        assertNull(RejectRouteSupport.originalCondition(unconditional));
        // 手写的正向条件（包括模板里的 ${decision == 'REJECT'}）不能被误认成驳回路由
        assertFalse(RejectRouteSupport.isCompiledRejectRoute("${days > 5}"));
        assertFalse(RejectRouteSupport.isCompiledRejectRoute("${decision == 'REJECT'}"));
        assertFalse(RejectRouteSupport.isCompiledRejectRoute(null));
    }

    @Test
    void selectTargetTakesFirstHit() {
        List<RejectRouteSupport.Candidate> candidates = List.of(
                new RejectRouteSupport.Candidate("Task_A", "${days > 5}"),
                new RejectRouteSupport.Candidate("Task_B", "${days > 3}"));
        String target = RejectRouteSupport.selectTarget(candidates,
                (expr, vars) -> {
                    int days = (Integer) vars.get("days");
                    return expr.contains("> 5") ? days > 5 : days > 3;
                },
                Map.of("days", 4), null);
        assertEquals("Task_B", target);
    }

    @Test
    void unconditionalCandidateWinsWithoutEvaluator() {
        // 无条件驳回路由：求值器抛错也不能拦住它（压根不该调用求值器）
        String target = RejectRouteSupport.selectTarget(
                List.of(new RejectRouteSupport.Candidate("Task_A", null)),
                (expr, vars) -> { throw new AssertionError("不该求值"); },
                Map.of(), null);
        assertEquals("Task_A", target);
    }

    @Test
    void brokenExpressionIsSkippedNotFatal() {
        List<String> warnings = new ArrayList<>();
        String target = RejectRouteSupport.selectTarget(
                List.of(new RejectRouteSupport.Candidate("Task_A", "${bad !!!}"),
                        new RejectRouteSupport.Candidate("Task_B", "${days > 5}")),
                (expr, vars) -> {
                    if (expr.contains("bad")) throw new IllegalArgumentException("表达式语法错误");
                    return true;
                },
                null, warnings::add);
        assertEquals("Task_B", target, "求值异常应跳过该线，继续找下一条，不能让驳回卡死");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Task_A"));
    }

    @Test
    void noMatchReturnsNullForFallback() {
        assertNull(RejectRouteSupport.selectTarget(
                List.of(new RejectRouteSupport.Candidate("Task_A", "${days > 5}")),
                (expr, vars) -> false, Map.of(), null));
        assertNull(RejectRouteSupport.selectTarget(List.of(),
                (expr, vars) -> true, Map.of(), null));
        assertNull(RejectRouteSupport.selectTarget(null,
                (expr, vars) -> true, Map.of(), null));
    }
}
