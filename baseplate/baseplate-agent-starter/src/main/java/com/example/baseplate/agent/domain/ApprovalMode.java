package com.example.baseplate.agent.domain;

/**
 * 工具审批策略，严格度 DENY &gt; ASK &gt; ALLOW。
 * INHERIT 表示跟随工具默认值；最终生效值还要与工具安全下限取更严格的一方。
 */
public enum ApprovalMode {
    INHERIT,
    ALLOW,
    ASK,
    DENY;

    /** 数字越大越严格，用于「取更严格」的比较。 */
    public int strictness() {
        return switch (this) {
            case ALLOW -> 0;
            case ASK -> 1;
            case DENY -> 2;
            case INHERIT -> -1;
        };
    }

    public static ApprovalMode stricter(ApprovalMode left, ApprovalMode right) {
        ApprovalMode a = left == null ? ApprovalMode.ALLOW : left;
        ApprovalMode b = right == null ? ApprovalMode.ALLOW : right;
        return a.strictness() >= b.strictness() ? a : b;
    }

    /**
     * 最终生效策略：先展开 INHERIT，再与工具安全下限取更严格的一方。
     * 破坏性工具即使配置成 ALLOW，也会被下限抬到 ASK。
     */
    public static ApprovalMode effective(ApprovalMode configured, ApprovalMode defaultApproval,
                                         ApprovalMode minApproval) {
        ApprovalMode resolved = (configured == null || configured == INHERIT)
                ? (defaultApproval == null ? ALLOW : defaultApproval) : configured;
        return stricter(resolved, minApproval == null ? ALLOW : minApproval);
    }
}
