package com.sqlcli.clickhouse;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.strategy.ClickHouseDatabaseStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CH-P1-030（不需要真容器的部分）：默认 LIMIT 注入逻辑的边界场景。
 *
 * {@code ClickHouseDatabaseStrategyTest} 已经覆盖了基本的有/无 LIMIT、SHOW、DESCRIBE
 * 场景；这里补齐 WITH CTE、UNION、SETTINGS 插入位置、LIMIT BY、大小写等纯字符串
 * 变换场景，跟真实执行结果无关，默认跑（不连容器）。真正“大结果集下 LIMIT 是否生效”
 * 由 {@code ClickHouseLargeResultSetIT}（Testcontainers）验证。
 */
class ClickHouseLimitInjectionEdgeCasesTest {

    private final ClickHouseDatabaseStrategy strategy = new ClickHouseDatabaseStrategy();

    private DatabaseConfig configWithLimit(int limit) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType("clickhouse");
        config.setDefaultQueryLimit(limit);
        return config;
    }

    @Test
    @DisplayName("WITH CTE 开头的查询也会被当作 SELECT 追加默认 LIMIT")
    void withCteGetsDefaultLimit() {
        String sql = "WITH x AS (SELECT 1) SELECT * FROM x";
        assertEquals("WITH x AS (SELECT 1) SELECT * FROM x LIMIT 100",
                strategy.preprocessSql(configWithLimit(100), sql));
    }

    @Test
    @DisplayName("UNION ALL 查询在整体末尾追加默认 LIMIT")
    void unionAllGetsDefaultLimitAtEnd() {
        String sql = "SELECT a FROM t1 UNION ALL SELECT a FROM t2";
        assertEquals("SELECT a FROM t1 UNION ALL SELECT a FROM t2 LIMIT 50",
                strategy.preprocessSql(configWithLimit(50), sql));
    }

    @Test
    @DisplayName("SETTINGS 子句存在时，LIMIT 插到 SETTINGS 之前")
    void limitInsertedBeforeSettingsClause() {
        String sql = "SELECT * FROM system.tables SETTINGS max_threads=4";
        assertEquals("SELECT * FROM system.tables LIMIT 100 SETTINGS max_threads=4",
                strategy.preprocessSql(configWithLimit(100), sql));
    }

    @Test
    @DisplayName("FORMAT 子句存在时不追加 LIMIT")
    void formatClauseSuppressesDefaultLimit() {
        String sql = "SELECT * FROM system.tables FORMAT JSON";
        assertEquals(sql, strategy.preprocessSql(configWithLimit(100), sql));
    }

    @Test
    @DisplayName("ClickHouse LIMIT n BY 语法已算显式 LIMIT，不再追加")
    void limitByIsTreatedAsExplicitLimit() {
        String sql = "SELECT a, count() AS c FROM t GROUP BY a ORDER BY c DESC LIMIT 3 BY a";
        assertEquals(sql, strategy.preprocessSql(configWithLimit(100), sql));
    }

    @Test
    @DisplayName("小写关键字的 SELECT 同样会被追加默认 LIMIT")
    void lowercaseSelectGetsDefaultLimit() {
        String sql = "select * from system.tables";
        assertEquals("select * from system.tables LIMIT 100",
                strategy.preprocessSql(configWithLimit(100), sql));
    }

    @Test
    @DisplayName("defaultQueryLimit <= 0 时不追加 LIMIT")
    void nonPositiveDefaultLimitIsNoop() {
        String sql = "SELECT * FROM system.tables";
        assertEquals(sql, strategy.preprocessSql(configWithLimit(0), sql));
    }

    @Test
    @DisplayName("前导空白和末尾分号被规整后再判断，不影响 LIMIT 注入")
    void trailingSemicolonAndWhitespaceHandled() {
        String sql = "  \n  SELECT * FROM system.tables;\n  ";
        assertEquals("SELECT * FROM system.tables LIMIT 100",
                strategy.preprocessSql(configWithLimit(100), sql));
    }
}
