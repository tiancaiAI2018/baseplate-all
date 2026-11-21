package com.example.baseplate.agent.config;

import java.util.Locale;

/**
 * 内部工具对模型的暴露方式，三者互斥。
 *
 * <p>{@code DIRECT} 保留原始行为（每个工具单独下发 schema），适合工具很少或调试场景；
 * {@code CLI} 与 {@code META} 都是收敛方案，区别只在入口形态：一个是命令行脚本，
 * 一个是三个结构化元工具。工具越多，收敛方案的 token 与准确率收益越大。</p>
 */
public enum ToolExposureMode {

    /** 直接暴露：每个内部工具各自一条 schema。 */
    DIRECT,

    /** 命令行入口：只暴露 {@code baseplate}，用 find/show/call 按需检索执行。 */
    CLI,

    /** 元工具入口：暴露 {@code tool_search} / {@code tool_describe} / {@code tool_call}。 */
    META;

    public static ToolExposureMode parse(String value) {
        if (value == null || value.isBlank()) {
            return CLI;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CLI;
        }
    }
}
