package com.example.baseplate.agent.application;

import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

/**
 * 把平台配置映射为 AgentScope 官方的 {@link CompactionConfig}。
 *
 * <p>压缩算法完全交给 harness 的 {@code CompactionMiddleware} /
 * {@code ConversationCompactor}，这里只负责阈值与保留条数的换算：
 * 平台沿用「上下文占用百分比」的配置口径，官方按 token 数触发。</p>
 */
public final class AgentCompactionConfig {

    private AgentCompactionConfig() {
    }

    /** 自动压缩：token 阈值 = 上下文窗口 × 占用百分比，保留最近 N 条。 */
    public static CompactionConfig automatic(AgentProperties properties, AgentDefinition agent) {
        int window = contextWindow(properties, agent);
        int triggerTokens = Math.max(1, (int) ((long) window * properties.getCompactionThreshold() / 100));
        return base(properties.getCompactionKeepMessages())
                // triggerMessages 沿用官方默认 50：短消息很多时也能提前压缩
                .triggerTokens(triggerTokens)
                .build();
    }

    /** 手动压缩：强制触发，沿用保留条数配置。 */
    public static CompactionConfig manual(AgentProperties properties, int keepMessages) {
        return base(keepMessages)
                .triggerMessages(1)
                .triggerTokens(0)
                .build();
    }

    /** 上下文窗口可由 Agent 的 modelParams.contextWindow 覆盖。 */
    public static int contextWindow(AgentProperties properties, AgentDefinition agent) {
        Object configured = agent == null || agent.modelParams() == null
                ? null : agent.modelParams().get("contextWindow");
        int value = configured instanceof Number number
                ? number.intValue() : properties.getContextWindow();
        return Math.max(1, value);
    }

    private static CompactionConfig.Builder base(int keepMessages) {
        int keep = Math.max(2, Math.min(keepMessages, 200));
        return CompactionConfig.builder()
                .keepMessages(keep)
                // 用消息条数保留尾部；官方默认的动态 token 预算与平台配置口径不一致
                .keepTokens(0)
                // 平台没有 harness 的工作区目录，关闭长期记忆抽取与原始消息落盘；
                // 摘要仍由官方 prompt 生成，工具结果裁剪 / 参数截断保持官方默认
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false);
    }
}
