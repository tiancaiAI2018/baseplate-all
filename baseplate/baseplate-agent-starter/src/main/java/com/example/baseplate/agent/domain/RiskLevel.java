package com.example.baseplate.agent.domain;

/** 工具风险级别：只读 / 写入 / 破坏性。破坏性工具的安全下限是「需人工确认」。 */
public enum RiskLevel {
    READ,
    WRITE,
    DESTRUCTIVE
}
