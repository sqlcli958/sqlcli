package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteDatabaseStrategy 的专属契约测试。
 *
 * <p>没有并进 {@link DatabaseStrategyContractTest} 的参数化列表：那份契约测试是围绕
 * host/port/database 结构化配置设计的（{@code configFor()} 固定塞 host=localhost，
 * {@code buildJdbcUrlFromStructuredConfig} 断言 URL 里必须出现 "localhost"，
 * {@code capabilitiesAreDeclaredAndSane} 断言 defaultPort 必须 > 0）——这些对 SQLite
 * 这种"连接就是选一个文件"的数据源统统不适用，硬塞进去要么改测试语义要么加一堆
 * if-sqlite 特判，两者都比单独写一份小测试更糟。可复用的断言（能力自洽、policy 一致、
 * 标识符转义、defaultSchema 不抛异常）在这里各自重新断言一遍。
 */
class SqliteDatabaseStrategyTest {

    private SqliteDatabaseStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SqliteDatabaseStrategy();
    }

    @Test
    void testType() {
        assertEquals("sqlite", strategy.type());
        assertSame(SqliteDatabaseStrategy.class, DatabaseStrategies.resolve("sqlite").getClass());
    }

    @Test
    void capabilitiesReflectSqliteReality() {
        DatabaseCapabilities caps = strategy.capabilities();
        // 实测 sqlite-jdbc 3.46.1.3: DatabaseMetaData.supportsTransactions()/supportsSavepoints() 均为 true。
        assertTrue(caps.isSupportsTransactions());
        assertTrue(caps.isSupportsRecoverySql());
        assertTrue(caps.isAffectedRowsReliable());
        assertTrue(caps.isSupportsDdl());
        assertFalse(caps.isSupportsShow(), "SQLite 语法里没有 SHOW 语句");
        assertEquals(0, caps.getDefaultPort(), "文件型数据源没有端口，跟 GenericDatabaseStrategy 的约定一致");
        assertEquals("\"", caps.getIdentifierQuote());
        assertEquals("main", caps.getDefaultDatabase());
    }

    @Test
    void policyMatchesStandardRdbms() {
        SqlExecutionPolicy policy = strategy.executionPolicy();
        assertTrue(policy.isAllowStandardUpdate());
        assertTrue(policy.isAllowStandardDelete());
        assertTrue(policy.isGenerateRecoverySql());
        assertTrue(policy.isRequireReadonlyGuard());
        assertFalse(policy.isAllowMultipleStatements());
        assertEquals(strategy.capabilities().isSupportsRecoverySql(), policy.isGenerateRecoverySql());
    }

    @Test
    void defaultSchemaIsAlwaysMain() {
        assertEquals("main", strategy.defaultSchema(new DatabaseConfig()));
    }

    @Test
    void quoteIdentifierUsesDoubleQuotesAndEscapes() {
        assertEquals("\"my_table\"", strategy.quoteIdentifier("my_table"));
        assertEquals("\"a\"\"b\"", strategy.quoteIdentifier("a\"b"));
    }

    @Test
    void qualifyTableNameHandlesSchemaPresenceAndAbsence() {
        assertEquals("\"main\".\"t1\"", strategy.qualifyTableName("main", "t1"));
        assertEquals("\"t1\"", strategy.qualifyTableName(null, "t1"));
    }

    @Test
    void connectionHintsNeverEmpty() {
        List<String> hints = strategy.buildConnectionHints(new DatabaseConfig(), null, null);
        assertNotNull(hints);
        assertFalse(hints.isEmpty());
    }

    @Nested
    @DisplayName("buildJdbcUrl: 只拼字符串，不做 I/O")
    class BuildJdbcUrlTests {

        @TempDir
        Path tempDir;

        @Test
        void buildsForwardSlashUrlFromDatabaseFieldEvenWhenFileIsMissing() {
            // buildJdbcUrl 不做存在性检查（那是 applyConnectionProperties 的职责，见类注释：
            // DatabaseConfig#buildBaseJdbcUrl 在 jdbcUrl 已设置时根本不会调用这个方法，
            // 检查写在这里对真实别名等于没写）。这里只验证纯字符串拼接，且用正斜杠，
            // 和 AliasConfigValidator/CLI 向导落库的形式一致。
            DatabaseConfig config = new DatabaseConfig();
            config.setDatabase(tempDir.resolve("does-not-exist.db").toString());

            String url = strategy.buildJdbcUrl(config);
            String expected = "jdbc:sqlite:"
                    + tempDir.resolve("does-not-exist.db").toAbsolutePath().normalize().toString().replace('\\', '/');
            assertEquals(expected, url);
        }

        @Test
        void existingFileBuildsAbsoluteForwardSlashUrl() throws IOException {
            Path dbFile = Files.createFile(tempDir.resolve("shop.db"));
            DatabaseConfig config = new DatabaseConfig();
            config.setDatabase(dbFile.toString());

            String url = strategy.buildJdbcUrl(config);
            assertEquals("jdbc:sqlite:" + dbFile.toAbsolutePath().normalize().toString().replace('\\', '/'), url);
        }

        @Test
        void explicitJdbcUrlIsPreferredOverDatabaseField() {
            DatabaseConfig config = new DatabaseConfig();
            config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("via-url.db").toString().replace('\\', '/'));
            config.setDatabase("should-be-ignored.db");

            String url = strategy.buildJdbcUrl(config);
            assertTrue(url.contains("via-url.db"));
            assertFalse(url.contains("should-be-ignored"));
        }

        @Test
        void blankDatabaseAndUrlFailsWithConfigError() {
            DatabaseConfig config = new DatabaseConfig();
            assertThrows(IllegalArgumentException.class, () -> strategy.buildJdbcUrl(config));
        }
    }

    @Nested
    @DisplayName("applyConnectionProperties: 连接前的文件存在性检查")
    class ApplyConnectionPropertiesTests {

        @TempDir
        Path tempDir;

        @Test
        void missingFileFailsWithClearAbsolutePathMessage() {
            DatabaseConfig config = new DatabaseConfig();
            config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("does-not-exist.db").toString().replace('\\', '/'));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> strategy.applyConnectionProperties(config, new java.util.Properties()));
            assertTrue(ex.getMessage().contains("不存在"));
            assertTrue(ex.getMessage().contains("does-not-exist.db"),
                    "错误信息必须包含解析后的绝对路径，方便定位路径拼错: " + ex.getMessage());
        }

        @Test
        void existingFilePassesSilently() throws IOException {
            Path dbFile = Files.createFile(tempDir.resolve("shop.db"));
            DatabaseConfig config = new DatabaseConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.toString().replace('\\', '/'));

            assertDoesNotThrow(() -> strategy.applyConnectionProperties(config, new java.util.Properties()));
        }

        @Test
        void createIfMissingOptInSkipsTheCheck() {
            DatabaseConfig config = new DatabaseConfig();
            config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("new.db").toString().replace('\\', '/'));
            config.setParams(Map.of("createIfMissing", "true"));

            assertDoesNotThrow(() -> strategy.applyConnectionProperties(config, new java.util.Properties()));
        }

        @Test
        void inMemoryUrlSkipsTheCheck() {
            DatabaseConfig config = new DatabaseConfig();
            config.setJdbcUrl("jdbc:sqlite::memory:");

            assertDoesNotThrow(() -> strategy.applyConnectionProperties(config, new java.util.Properties()));
        }
    }
}
