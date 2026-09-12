package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-P3-002：所有已注册的 DatabaseStrategy 必须通过同一组契约测试。
 * 新增数据库类型时把它加进 {@link #strategies()} 即可。
 */
class DatabaseStrategyContractTest {

    static Stream<DatabaseStrategy> strategies() {
        return Stream.of(
                new MySqlDatabaseStrategy(),
                new OracleDatabaseStrategy(),
                new PostgreSqlDatabaseStrategy(),
                new ClickHouseDatabaseStrategy());
    }

    private static DatabaseConfig configFor(DatabaseStrategy strategy) {
        DatabaseConfig config = new DatabaseConfig();
        config.setAliasName("contract-test");
        config.setType(strategy.type());
        config.setHost("localhost");
        config.setPort(strategy.capabilities().getDefaultPort() > 0
                ? strategy.capabilities().getDefaultPort() : 1234);
        config.setDatabase("testdb");
        config.setUsername("tester");
        config.setServiceName("testsvc"); // Oracle 结构化配置需要 serviceName 或 sid
        return config;
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void typeIsNonBlankLowercaseAndRegistered(DatabaseStrategy strategy) {
        String type = strategy.type();
        assertNotNull(type);
        assertFalse(type.isBlank());
        assertEquals(type.toLowerCase(Locale.ROOT), type, "type 必须小写，注册表按小写解析");
        assertSame(DatabaseStrategies.resolve(type).getClass(), strategy.getClass(),
                "策略必须已注册到 DatabaseStrategies");
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void capabilitiesAreDeclaredAndSane(DatabaseStrategy strategy) {
        DatabaseCapabilities caps = strategy.capabilities();
        assertNotNull(caps);
        assertTrue(caps.getDefaultPort() > 0, "必须声明默认端口");
        assertNotEquals("'", caps.getIdentifierQuote(),
                "单引号是字符串字面量引号，不是标识符引用符");
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void policyIsConsistentWithCapabilities(DatabaseStrategy strategy) {
        DatabaseCapabilities caps = strategy.capabilities();
        SqlExecutionPolicy policy = strategy.executionPolicy();
        assertNotNull(policy);
        assertEquals(caps.isSupportsRecoverySql(), policy.isGenerateRecoverySql(),
                "恢复 SQL 生成必须与能力声明一致");
        if (policy.isGenerateRecoverySql()) {
            assertTrue(caps.isSupportsTransactions(),
                    "恢复 SQL 依赖事务回滚，无事务的数据库不能声明支持恢复");
        }
        if (!policy.isAllowStandardUpdate()) {
            assertNotNull(policy.getUnsupportedUpdateMessage(), "拒绝标准 UPDATE 必须给出理由");
            assertFalse(policy.getUnsupportedUpdateMessage().isBlank());
        }
        if (!policy.isAllowStandardDelete()) {
            assertNotNull(policy.getUnsupportedDeleteMessage(), "拒绝标准 DELETE 必须给出理由");
            assertFalse(policy.getUnsupportedDeleteMessage().isBlank());
        }
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void quoteIdentifierWrapsAndEscapes(DatabaseStrategy strategy) {
        String quoted = strategy.quoteIdentifier("my_table");
        assertTrue(quoted.contains("my_table"));

        String quote = strategy.capabilities().getIdentifierQuote();
        if (quote != null && !quote.isBlank()) {
            String tricky = "a" + quote + "b";
            String quotedTricky = strategy.quoteIdentifier(tricky);
            assertTrue(quotedTricky.startsWith(quote) && quotedTricky.endsWith(quote));
            assertTrue(quotedTricky.contains(quote + quote),
                    "内嵌引用符必须双写转义，否则是注入点：" + quotedTricky);
        }
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void qualifyTableNameHandlesSchemaPresenceAndAbsence(DatabaseStrategy strategy) {
        String withSchema = strategy.qualifyTableName("s1", "t1");
        assertTrue(withSchema.contains("s1") && withSchema.contains("t1") && withSchema.contains("."));
        String withoutSchema = strategy.qualifyTableName(null, "t1");
        assertTrue(withoutSchema.contains("t1"));
        assertFalse(withoutSchema.contains("."));
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void buildJdbcUrlFromStructuredConfig(DatabaseStrategy strategy) {
        String url = strategy.buildJdbcUrl(configFor(strategy));
        assertNotNull(url);
        assertTrue(url.startsWith("jdbc:"), "JDBC URL 必须以 jdbc: 开头: " + url);
        assertTrue(url.contains("localhost"), "结构化配置的 host 必须体现在 URL 中: " + url);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void preprocessSqlKeepsNonSelectUntouchedExceptSemicolon(DatabaseStrategy strategy) {
        DatabaseConfig config = configFor(strategy);
        String insert = "INSERT INTO t (id) VALUES (1)";
        assertEquals(insert, strategy.preprocessSql(config, insert + ";"),
                "非 SELECT 语句只允许去掉尾分号，不得改写");
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void preprocessSqlAppliesDefaultLimitExactlyOnce(DatabaseStrategy strategy) {
        DatabaseConfig config = configFor(strategy);
        config.setDefaultQueryLimit(50);
        String processed = strategy.preprocessSql(config, "SELECT * FROM t");
        assertNotNull(processed);
        assertTrue(processed.toUpperCase(Locale.ROOT).contains("50")
                        && (processed.toUpperCase(Locale.ROOT).contains("LIMIT")
                        || processed.toUpperCase(Locale.ROOT).contains("ROWNUM")),
                "默认查询上限必须生效: " + processed);
        // 已有显式 LIMIT 时不得重复追加
        String explicit = "SELECT * FROM t LIMIT 5";
        String reprocessed = strategy.preprocessSql(config, explicit);
        assertFalse(reprocessed.contains("50"), "显式 LIMIT 不得重复追加默认上限: " + reprocessed);
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void connectionHintsNeverEmptyAndNullSafe(DatabaseStrategy strategy) {
        List<String> hints = strategy.buildConnectionHints(configFor(strategy), null, null);
        assertNotNull(hints);
        assertFalse(hints.isEmpty(), "连接失败时必须至少给出一条排查提示");
        assertTrue(hints.stream().allMatch(h -> h != null && !h.isBlank()));
    }

    @ParameterizedTest
    @MethodSource("strategies")
    void defaultSchemaDoesNotThrow(DatabaseStrategy strategy) {
        assertDoesNotThrow(() -> strategy.defaultSchema(configFor(strategy)));
    }
}
