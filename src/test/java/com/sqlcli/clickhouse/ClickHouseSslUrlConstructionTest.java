package com.sqlcli.clickhouse;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.ClickHouseDatabaseStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CH-P1-029：HTTPS/SSL 参数构造测试。
 *
 * 不连网、不要求真实证书——只断言 URL 拼接和连接属性构造这两段纯字符串/Map 逻辑。
 * 真正握手 SSL 的场景属于 CH-P1-027/028 的 Testcontainers 集成测试范畴。
 */
class ClickHouseSslUrlConstructionTest {

    private final ClickHouseDatabaseStrategy strategy = new ClickHouseDatabaseStrategy();

    @Nested
    @DisplayName("legacy jdbc:ch:https:// / jdbc:clickhouse:https:// 归一化")
    class LegacyHttpsNormalization {

        @Test
        @DisplayName("jdbc:ch:https:// 归一化为 jdbc:clickhouse:// 并补上 ssl=true")
        void chHttpsSchemeGetsSslParam() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");
            config.setJdbcUrl("jdbc:ch:https://ch-cloud.example.com:8443/analytics");

            assertEquals("jdbc:clickhouse://ch-cloud.example.com:8443/analytics?ssl=true",
                    config.buildJdbcUrl());
        }

        @Test
        @DisplayName("jdbc:clickhouse:https:// 归一化为 jdbc:clickhouse:// 并补上 ssl=true")
        void clickhouseHttpsSchemeGetsSslParam() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");
            config.setJdbcUrl("jdbc:clickhouse:https://ch-cloud.example.com:8443/analytics");

            assertEquals("jdbc:clickhouse://ch-cloud.example.com:8443/analytics?ssl=true",
                    config.buildJdbcUrl());
        }

        @Test
        @DisplayName("已经带 ssl= 参数时不重复追加")
        void doesNotDuplicateExistingSslParam() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");
            config.setJdbcUrl("jdbc:clickhouse:https://ch-cloud.example.com:8443/analytics?ssl=true");

            String url = config.buildJdbcUrl();
            assertEquals("jdbc:clickhouse://ch-cloud.example.com:8443/analytics?ssl=true", url);
            assertEquals(1, url.split("ssl=", -1).length - 1, "ssl= 只应出现一次");
        }
    }

    @Nested
    @DisplayName("结构化 host/port/database + params 拼接 SSL 相关查询参数")
    class StructuredConfigWithSslParams {

        @Test
        @DisplayName("host/port(8443)/database 加 ssl/sslmode/sslrootcert 参数，整体 URL 正确拼接并编码")
        void appendsSslParamsWithEncoding() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");
            config.setHost("ch-server");
            config.setPort(8443);
            config.setDatabase("analytics");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("ssl", "true");
            params.put("sslmode", "strict");
            params.put("sslrootcert", "/etc/certs/ca.pem");
            config.setParams(params);

            String url = config.buildJdbcUrl();

            assertEquals(
                    "jdbc:clickhouse://ch-server:8443/analytics?ssl=true&sslmode=strict&sslrootcert=%2Fetc%2Fcerts%2Fca.pem",
                    url);
        }

        @Test
        @DisplayName("不传 SSL 相关 params 时，URL 不含任何 ssl 查询参数")
        void plainHttpHasNoSslParam() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");
            config.setHost("ch-server");
            config.setPort(8123);
            config.setDatabase("analytics");

            String url = config.buildJdbcUrl();

            assertEquals("jdbc:clickhouse://ch-server:8123/analytics", url);
            assertFalse(url.contains("ssl"));
        }
    }

    @Nested
    @DisplayName("applyConnectionProperties 把 SSL 相关 params 透传进 Properties")
    class ApplyConnectionPropertiesSsl {

        @Test
        @DisplayName("params 里的 ssl/sslmode 被写入 Properties，且不覆盖已有值")
        void sslParamsFlowIntoProperties() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("ssl", "true");
            params.put("sslmode", "strict");
            config.setParams(params);

            Properties properties = new Properties();
            // 模拟调用方（ConnectionManager）已经先设置过 client_name，applyConnectionProperties
            // 只用 putIfAbsent，不应覆盖调用方显式设置的值。
            properties.setProperty("client_name", "custom-agent");

            strategy.applyConnectionProperties(config, properties);

            assertEquals("custom-agent", properties.getProperty("client_name"));
            assertEquals("true", properties.getProperty("ssl"));
            assertEquals("strict", properties.getProperty("sslmode"));
        }

        @Test
        @DisplayName("未设置 client_name 时，applyConnectionProperties 补上默认值")
        void defaultsClientNameWhenAbsent() {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("clickhouse");

            Properties properties = new Properties();
            strategy.applyConnectionProperties(config, properties);

            assertNotNull(properties.getProperty("client_name"));
            assertTrue(properties.getProperty("client_name").startsWith("sql-cli/"));
        }
    }
}
