package com.example.baseplate.workflow.application;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 驳回路由（在设计图上画「后→前」的连线来表达退回到哪个节点）的共享语义。
 *
 * 背景：驳回过去固定走 {@code moveActivityIdTo(上一节点)}，完全不看法图上的连线；
 * 用户画的带条件回退线因此只是装饰。这里把「哪些线参与驳回、条件怎么选目标」收敛成
 * 纯函数，既给 NodeConfigCompiler 编译期用，也给 FlowableProcessEngineAdapter 运行期用，
 * 便于单测覆盖而不必启动 Flowable。
 *
 * 语义：
 * - 只有连线配置里显式勾了 {@code rejectRoute=true} 的线才是驳回路由，不靠历史推断；
 * - 编译期给这些线的 conditionExpression 加 {@code decision == 'REJECT'} 守卫，
 *   保证正常审批（APPROVE）时引擎不会沿回退线正向绕回去；
 * - 运行期驳回时只求值这些线的原始条件，命中哪条就退到哪条线的目标节点；
 * - 没勾线、条件都没命中或求值异常 → 返回 null，调用方兜底退回上一个已完成任务。
 */
public final class RejectRouteSupport {

    /** conditionConfig 里标记「这条线是驳回路由」的键。 */
    public static final String REJECT_ROUTE = "rejectRoute";
    /** 编译产物上保留原始条件表达式的扩展属性（仅给设计器回读用，Flowable 解析会丢弃）。 */
    public static final String REJECT_CONDITION_ATTR = "rejectCondition";
    /** 编译产物上标记驳回路由的扩展属性（仅给设计器回读用，Flowable 解析会丢弃）。 */
    public static final String REJECT_ROUTE_ATTR = "rejectRoute";
    /** 正向流转的守卫变量：正常审批时该条件恒 false，回退线不参与正向求值。 */
    public static final String GUARD = "decision == 'REJECT'";
    /** 运行期识别驳回路由的唯一可靠标记：Flowable 保留 conditionExpression 文本，但丢弃自定义属性。 */
    private static final String MARKER = "${" + GUARD + " && ";
    /** 无条件驳回路由的守卫：用 `&& true` 带上标记，避免和手写的 `${decision == 'REJECT'}` 混淆。 */
    private static final String UNCONDITIONAL = MARKER + "true}";

    private RejectRouteSupport() {
    }

    /** 连线是否显式标记为驳回路由；大小写与布尔/字符串都接受，其余一律 false。 */
    public static boolean isRejectRoute(Map<String, Object> conditionConfig) {
        if (conditionConfig == null) {
            return false;
        }
        Object raw = conditionConfig.get(REJECT_ROUTE);
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw instanceof String value && Boolean.parseBoolean(value.trim());
    }

    /**
     * 给驳回路由的原始表达式加正向守卫。
     * 输入可以是 {@code ${...}} 或裸表达式；输出一定是 {@code ${...}}。
     * 空表达式表示「无条件命中」，仍要加守卫，否则正常审批会误走这条线。
     */
    public static String guardedExpression(String rawExpression) {
        String expression = normalize(rawExpression);
        if (expression == null) {
            return UNCONDITIONAL;
        }
        String inner = expression.substring(2, expression.length() - 1);
        return MARKER + "(" + inner + ")}";
    }

    /** 编译后的条件是否是驳回路由（正向流转不会带这个标记）。 */
    public static boolean isCompiledRejectRoute(String compiledExpression) {
        return compiledExpression != null && compiledExpression.trim().startsWith(MARKER);
    }

    /**
     * 从编译后的条件里还原原始表达式；返回 null 表示无条件命中，
     * 返回非 null 则是形如 {@code ${...}} 的原始条件。
     */
    public static String originalCondition(String compiledExpression) {
        if (!isCompiledRejectRoute(compiledExpression)) {
            return null;
        }
        String value = compiledExpression.trim();
        if (UNCONDITIONAL.equals(value)) {
            return null;
        }
        String prefix = MARKER + "(";
        if (!value.startsWith(prefix) || !value.endsWith(")}")) {
            return null;
        }
        return "${" + value.substring(prefix.length(), value.length() - 2) + "}";
    }

    /** 统一表达式包装：null/空白返回 null，否则保证以 ${ 开头、} 结尾。 */
    public static String normalize(String rawExpression) {
        if (rawExpression == null || rawExpression.isBlank()) {
            return null;
        }
        String expression = rawExpression.trim();
        if (!expression.startsWith("${")) {
            expression = "${" + expression + "}";
        }
        return expression.endsWith("}") ? expression : expression + "}";
    }

    /** 一条候选驳回路由：目标节点 + 原始条件表达式（null 表示无条件命中）。 */
    public record Candidate(String targetNodeId, String expression) {
    }

    /** 表达式求值器：由适配器用 Flowable ExpressionManager 实现，纯函数层不依赖引擎。 */
    public interface Evaluator {
        boolean evaluate(String expression, Map<String, Object> variables);
    }

    /**
     * 按顺序取第一条命中的驳回路由。
     * - 空表达式视为无条件命中；
     * - 求值异常只告警并跳过该线，绝不阻断驳回（业务不能因为配置写错就卡死）；
     * - 全部未命中返回 null，由调用方兜底退上一节点。
     */
    public static String selectTarget(List<Candidate> candidates, Evaluator evaluator,
                                      Map<String, Object> variables, Consumer<String> warningSink) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.targetNodeId() == null || candidate.targetNodeId().isBlank()) {
                continue;
            }
            String expression = normalize(candidate.expression());
            if (expression == null) {
                return candidate.targetNodeId();
            }
            boolean hit;
            try {
                hit = evaluator.evaluate(expression, variables == null ? Map.of() : variables);
            } catch (RuntimeException ex) {
                if (warningSink != null) {
                    warningSink.accept("驳回路由条件求值失败，已跳过：节点 "
                            + candidate.targetNodeId() + "，条件 " + expression + "，" + ex.getMessage());
                }
                continue;
            }
            if (hit) {
                return candidate.targetNodeId();
            }
        }
        return null;
    }
}
