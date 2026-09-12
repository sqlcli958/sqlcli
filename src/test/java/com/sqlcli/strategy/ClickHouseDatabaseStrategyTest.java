package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClickHouseDatabaseStrategyTest {

    private ClickHouseDatabaseStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ClickHouseDatabaseStrategy();
    }

    @Test
    @DisplayName("type() returns clickhouse")
    void testType() {
        assertEquals("clickhouse", strategy.type());
    }

    @Test
    void defaultSchemaComesFromCanonicalUrl() {
        DatabaseConfig config = new DatabaseConfig();
        config.setJdbcUrl("jdbc:clickhouse://db.example.com:8123/analytics?ssl=true");

        assertEquals("analytics", strategy.defaultSchema(config));
    }

    @Nested
    @DisplayName("Capabilities")
    class CapabilitiesTests {

        @Test
        @DisplayName("capabilities() returns CLICKHOUSE_DEFAULTS")
        void testCapabilities() {
            DatabaseCapabilities caps = strategy.capabilities();

            assertFalse(caps.isSupportsTransactions());
            assertFalse(caps.isSupportsRecoverySql());
            assertFalse(caps.isAffectedRowsReliable());

            assertTrue(caps.isSupportsDdl());
            assertTrue(caps.isSupportsShow());
            assertTrue(caps.isUsesSchemaAsDatabase());

            assertEquals(8123, caps.getDefaultPort());
            assertEquals(8443, caps.getDefaultSecurePort());
            assertEquals("`", caps.getIdentifierQuote());
            assertEquals("default", caps.getDefaultDatabase());
        }
    }

    @Nested
    @DisplayName("Execution Policy")
    class ExecutionPolicyTests {

        @Test
        @DisplayName("executionPolicy() returns CLICKHOUSE_POLICY")
        void testExecutionPolicy() {
            SqlExecutionPolicy policy = strategy.executionPolicy();

            assertFalse(policy.isAllowStandardUpdate());
            assertFalse(policy.isAllowStandardDelete());
            assertFalse(policy.isGenerateRecoverySql());
            assertFalse(policy.isAllowMultipleStatements());

            assertTrue(policy.isAllowAlterMutation());
            assertTrue(policy.isAllowInsert());
            assertTrue(policy.isAllowDdl());
            assertTrue(policy.isAllowShow());
            assertTrue(policy.isRequireReadonlyGuard());

            assertNotNull(policy.getUnsupportedUpdateMessage());
            assertNotNull(policy.getUnsupportedDeleteMessage());
        }
    }

    @Nested
    @DisplayName("JDBC URL Construction")
    class BuildJdbcUrlTests {

        @Test
        @DisplayName("buildJdbcUrl with host, port, and database")
        void testBuildJdbcUrl_HostPortDatabase() {
            DatabaseConfig config = new DatabaseConfig();
            config.setHost("ch-server");
            config.setPort(8123);
            config.setDatabase("analytics");

            assertEquals("jdbc:clickhouse://ch-server:8123/analytics", strategy.buildJdbcUrl(config));
        }

        @Test
        @DisplayName("buildJdbcUrl with default values")
        void testBuildJdbcUrl_Defaults() {
            DatabaseConfig config = new DatabaseConfig();

            assertEquals("jdbc:clickhouse://localhost:8123/default", strategy.buildJdbcUrl(config));
        }

        @Test
        @DisplayName("buildJdbcUrl keeps canonical form for secure port")
        void testBuildJdbcUrl_HttpsPort() {
            DatabaseConfig config = new DatabaseConfig();
            config.setHost("ch-cloud");
            config.setPort(8443);
            config.setDatabase("analytics");

            assertEquals("jdbc:clickhouse://ch-cloud:8443/analytics", strategy.buildJdbcUrl(config));
        }

        @Test
        @DisplayName("buildJdbcUrl normalizes an explicit legacy JDBC URL")
        void testBuildJdbcUrl_ExplicitJdbcUrl() {
            DatabaseConfig config = new DatabaseConfig();
            config.setJdbcUrl("jdbc:ch:http://custom:8123/mydb");

            assertEquals("jdbc:clickhouse://custom:8123/mydb", strategy.buildJdbcUrl(config));
        }
    }

    @Nested
    @DisplayName("Identifier Quoting")
    class QuoteIdentifierTests {

        @Test
        @DisplayName("quoteIdentifier wraps with backticks")
        void testQuoteIdentifier() {
            assertEquals("`table name`", strategy.quoteIdentifier("table name"));
        }

        @Test
        @DisplayName("quoteIdentifier escapes embedded backticks")
        void testQuoteIdentifier_Escaping() {
            assertEquals("`col``name`", strategy.quoteIdentifier("col`name"));
        }
    }

    @Nested
    @DisplayName("Table Name Qualification")
    class QualifyTableNameTests {

        @Test
        @DisplayName("qualifyTableName with schema produces schema.table format")
        void testQualifyTableName_WithSchema() {
            assertEquals("`analytics`.`events`", strategy.qualifyTableName("analytics", "events"));
        }

        @Test
        @DisplayName("qualifyTableName without schema produces table-only format")
        void testQualifyTableName_NoSchema() {
            assertEquals("`events`", strategy.qualifyTableName(null, "events"));
        }
    }

    @Nested
    @DisplayName("SQL Preprocessing")
    class PreprocessSqlTests {

        @Test
        @DisplayName("SELECT without LIMIT gets default limit appended")
        void testPreprocessSql_SelectWithNoLimit() {
            DatabaseConfig config = new DatabaseConfig();
            config.setDefaultQueryLimit(100);

            assertEquals("SELECT * FROM system.tables LIMIT 100",
                    strategy.preprocessSql(config, "SELECT * FROM system.tables"));
        }

        @Test
        @DisplayName("SELECT with explicit LIMIT is not modified")
        void testPreprocessSql_SelectWithExplicitLimit() {
            DatabaseConfig config = new DatabaseConfig();
            config.setDefaultQueryLimit(100);

            assertEquals("SELECT * FROM system.tables LIMIT 10",
                    strategy.preprocessSql(config, "SELECT * FROM system.tables LIMIT 10"));
        }

        @Test
        @DisplayName("SHOW statements do not get LIMIT appended")
        void testPreprocessSql_ShowNoLimit() {
            DatabaseConfig config = new DatabaseConfig();
            config.setDefaultQueryLimit(100);

            assertEquals("SHOW DATABASES",
                    strategy.preprocessSql(config, "SHOW DATABASES"));
        }

        @Test
        @DisplayName("DESCRIBE statements do not get LIMIT appended")
        void testPreprocessSql_DescribeNoLimit() {
            DatabaseConfig config = new DatabaseConfig();
            config.setDefaultQueryLimit(100);

            assertEquals("DESCRIBE TABLE system.tables",
                    strategy.preprocessSql(config, "DESCRIBE TABLE system.tables"));
        }
    }
}
