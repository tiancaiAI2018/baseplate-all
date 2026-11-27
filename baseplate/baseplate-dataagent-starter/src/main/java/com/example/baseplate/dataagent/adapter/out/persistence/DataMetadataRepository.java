package com.example.baseplate.dataagent.adapter.out.persistence;

import com.example.baseplate.dataagent.application.SqlGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 基于 information_schema 的只读元数据查询；所有表名先经白名单与正则校验。 */
@Repository
public class DataMetadataRepository {

    private static final int TIMEOUT_MS = 10_000;

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    public DataMetadataRepository(JdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    public List<String> visibleTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name NOT LIKE 'act\\_%' ESCAPE '\\'
                  AND table_name NOT IN ('flyway_schema_history')
                ORDER BY table_name
                """, String.class);
    }

    public List<String> findTables(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String table : visibleTables()) {
            if (needle.isEmpty() || table.toLowerCase(Locale.ROOT).contains(needle)
                    || table.equalsIgnoreCase("t_" + needle.replaceAll("\\s+", "_"))
                    || table.toLowerCase(Locale.ROOT).contains("_" + needle)) {
                matched.add(table);
            }
        }
        return matched;
    }

    public List<Map<String, Object>> columns(String table) {
        requireTable(table);
        return jdbc.queryForList("""
                SELECT c.column_name AS column_name,
                       c.data_type AS data_type,
                       c.character_maximum_length AS max_length,
                       c.is_nullable AS nullable,
                       c.column_default AS default_value,
                       CASE WHEN k.column_name IS NULL THEN false ELSE true END AS primary_key
                FROM information_schema.columns c
                LEFT JOIN (
                    SELECT kcu.table_schema, kcu.table_name, kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                ) k ON k.table_schema = c.table_schema
                   AND k.table_name = c.table_name
                   AND k.column_name = c.column_name
                WHERE c.table_schema = 'public' AND c.table_name = ?
                ORDER BY c.ordinal_position
                """, table);
    }

    public List<Map<String, Object>> relatedTables(String table) {
        requireTable(table);
        return jdbc.queryForList("""
                SELECT kcu.table_name AS table_name,
                       kcu.column_name AS column_name,
                       ccu.table_name AS related_table,
                       ccu.column_name AS related_column,
                       rc.constraint_name AS constraint_name
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_name = rc.constraint_name
                JOIN information_schema.key_column_usage ccu
                  ON ccu.constraint_name = rc.unique_constraint_name
                WHERE kcu.table_schema = 'public'
                  AND (kcu.table_name = ? OR ccu.table_name = ?)
                ORDER BY kcu.table_name, kcu.column_name
                """, table, table);
    }

    public List<Map<String, Object>> preview(String table, int limit) {
        requireTable(table);
        return readOnly("SELECT * FROM \"" + table + "\" LIMIT " + Math.max(1, Math.min(limit, 20)));
    }

    public List<Map<String, Object>> query(String sql, int maxRows) {
        SqlGuard.Result guarded = SqlGuard.check(sql, maxRows);
        if (!guarded.allowed()) {
            throw new IllegalArgumentException(guarded.reason());
        }
        return readOnly(guarded.sql());
    }

    private List<Map<String, Object>> readOnly(String sql) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '" + TIMEOUT_MS + "ms'");
            return jdbc.queryForList(sql);
        });
    }

    private void requireTable(String table) {
        if (!SqlGuard.validTable(table) || !visibleTables().contains(table)) {
            throw new IllegalArgumentException("不可见或非法表名：" + table);
        }
    }

    public static Map<String, Object> cell(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        row.forEach((key, value) -> copy.put(key, value == null ? null : String.valueOf(value)));
        return copy;
    }
}
