package com.example.baseplate.api.model;

/** 硬过滤结果：不通过时 reason 会写进分单日志，便于回答「为什么没派给他」。 */
public record DispatchFilterResult(boolean passed, String reason) {

    private static final DispatchFilterResult PASS = new DispatchFilterResult(true, null);

    public static DispatchFilterResult pass() {
        return PASS;
    }

    public static DispatchFilterResult reject(String reason) {
        return new DispatchFilterResult(false, reason);
    }
}
