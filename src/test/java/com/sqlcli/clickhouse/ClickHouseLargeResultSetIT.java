package com.sqlcli.clickhouse;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.connection.SqlInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CH-P1-030（需要真容器的部分）：大结果集 smoke 测试。
 *
 * {@code system.numbers} 是 ClickHouse 内置的惰性无穷序列表，裸查询在真实场景下会
 * 一直吐数据。这里验证 {@link SqlInterceptor}（QueryExecutor 实际调用的同一入口，
 * 见 {@code com.sqlcli.connection.SqlInterceptor}）在没有显式 LIMIT 时注入
 * defaultQueryLimit 之后，真正执行返回的行数确实被截断——光测字符串拼接（见
 * {@code ClickHouseLimitInjectionEdgeCasesTest}）证明不了引擎真的服从它。
 *
 * <p>默认不参与构建，启用方式同 {@link ClickHouseIntegrationIT}：{@code mvn -Pit-clickhouse verify}。
 */
@Testcontainers
@Tag("clickhouse-it")
class ClickHouseLargeResultSetIT {

    @Container
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:23.8"));

    private final SqlInterceptor interceptor = new SqlInterceptor();

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                CLICKHOUSE.getJdbcUrl(), CLICKHOUSE.getUsername(), CLICKHOUSE.getPassword());
    }

    @Test
    @DisplayName("无 LIMIT 的大结果集查询被默认 LIMIT 截断")
    void defaultLimitCapsUnboundedQuery() throws Exception {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setDefaultQueryLimit(37);

        String sql = interceptor.preprocess(config, "SELECT number FROM system.numbers");
        assertTrue(sql.toUpperCase(Locale.ROOT).contains("LIMIT 37"));

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            assertEquals(37, count);
        }
    }

    @Test
    @DisplayName("显式 LIMIT 不被默认值覆盖，也不会被截断到更小的默认值")
    void explicitLimitIsPreservedNotOverriddenByDefault() throws Exception {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setDefaultQueryLimit(10);

        String sql = interceptor.preprocess(config, "SELECT number FROM system.numbers LIMIT 500");

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            assertEquals(500, count);
        }
    }
}
