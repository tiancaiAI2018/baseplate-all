package com.example.baseplate.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真库集成测试：PostgreSQL 新库 baseplate_dev。
 *
 * <p>连接信息只从环境变量读取（BASEPLATE_DB_URL / USER / PASSWORD），不落配置文件、不进版本库。
 * 未提供环境变量时 skip，且 skipped 不算通过——报告里必须写清。
 *
 * <p>应用启动期的 Flyway 是关闭的（决策 7：迁移在部署流水线独立执行），
 * 本测试显式调用 Flyway，模拟流水线那一步。
 */
class RealPostgresMigrationTest {

    @Test
    void flywayMigratesAndTableIsReadWrite() throws Exception {
        String url = env("BASEPLATE_DB_URL");
        String user = env("BASEPLATE_DB_USER");
        String password = env("BASEPLATE_DB_PASSWORD");
        assumeTrue(url != null && user != null && password != null,
                "未提供 BASEPLATE_DB_* 环境变量，跳过真库测试");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration/baseplate")
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
        assertNotNull(flyway.migrate(), "migrate 应返回结果");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO bp_module_registry(module_id, version, enabled, checksum)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (module_id) DO UPDATE
                       SET version = EXCLUDED.version,
                           enabled = EXCLUDED.enabled,
                           checksum = EXCLUDED.checksum,
                           loaded_at = now()
                    """)) {
                ps.setString(1, "smoke");
                ps.setString(2, "1.0.0");
                ps.setBoolean(3, true);
                ps.setString(4, "abc123");
                assertEquals(1, ps.executeUpdate());
            }

            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT version, enabled FROM bp_module_registry WHERE module_id = 'smoke'")) {
                assertTrue(rs.next(), "刚写入的模块记录应可查");
                assertEquals("1.0.0", rs.getString(1));
                assertTrue(rs.getBoolean(2));
            }

            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "flyway_schema_history 应记录迁移");
            }
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
