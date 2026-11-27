package com.example.baseplate.dataagent.application;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 只读 SQL 校验：data-agent 执行前的唯一入口，防止模型生成写操作或危险函数。 */
public final class SqlGuard {

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "COPY", "VACUUM", "ANALYZE", "REINDEX", "COMMENT",
            "CALL", "DO", "RESET", "SET");
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "pg_sleep", "pg_read_file", "pg_read_binary_file", "pg_ls_dir",
            "dblink", "pg_terminate_backend", "pg_cancel_backend", "lo_import", "lo_export");
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern LIMIT = Pattern.compile("(?i)\\blimit\\b");

    private SqlGuard() {
    }

    public record Result(boolean allowed, String reason, String sql) {
        static Result ok(String sql) {
            return new Result(true, null, sql);
        }

        static Result fail(String reason) {
            return new Result(false, reason, null);
        }
    }

    public static Result check(String raw, int maxRows) {
        if (raw == null || raw.isBlank()) {
            return Result.fail("SQL 为空");
        }
        String sql = raw.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            return Result.fail("一次只能执行一条 SQL");
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            return Result.fail("只允许 SELECT / WITH 只读查询");
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (Pattern.compile("(?i)\\b" + keyword + "\\b").matcher(sql).find()) {
                return Result.fail("禁止使用关键字 " + keyword);
            }
        }
        for (String function : FORBIDDEN_FUNCTIONS) {
            if (sql.toLowerCase(Locale.ROOT).contains(function)) {
                return Result.fail("禁止使用函数 " + function);
            }
        }
        if (!LIMIT.matcher(sql).find()) {
            sql = sql + " LIMIT " + Math.max(1, Math.min(maxRows, 1000));
        }
        return Result.ok(sql);
    }

    public static boolean validTable(String name) {
        return name != null && TABLE_NAME.matcher(name).matches();
    }
}
