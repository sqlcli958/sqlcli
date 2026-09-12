package com.sqlcli.profile;

import com.sqlcli.graph.workspace.SemanticType;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.DatabaseStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单列剖析引擎测试，全部用内存 SQLite——不连真库（任务硬约束）。
 *
 * <p>SQLite 对 {@code LENGTH()} 不做类型检查（动态类型），所以本类无法演示
 * "数字列长度统计因方言类型错误被跳过"这条真实存在于 PostgreSQL 上的分支；
 * 那条分支靠代码里的 try/catch 结构自证，这里改用一个不存在的列名触发聚合查询本身失败，
 * 覆盖"单列失败不拖垮整表"这条核心要求。
 */
class TableProfilerTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE t_customer (
                      id     INTEGER,
                      status TEXT,
                      phone  TEXT,
                      name   TEXT,
                      note   TEXT
                    )""");
            // 5 行：status 只有 2 个取值（低基数），phone 敏感且低基数，
            // name 每行都不同（高基数），note 有空值，id 是数值最值的对照。
            st.execute("INSERT INTO t_customer VALUES (1, 'active',   '13800000001', 'Alice', 'a note')");
            st.execute("INSERT INTO t_customer VALUES (2, 'inactive', '13800000002', 'Bob',   NULL)");
            st.execute("INSERT INTO t_customer VALUES (3, 'active',   '13800000001', 'Carol', 'cc')");
            st.execute("INSERT INTO t_customer VALUES (4, 'active',   '13800000002', 'Dave',  NULL)");
            st.execute("INSERT INTO t_customer VALUES (5, 'inactive', '13800000001', 'Eve',   'e')");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private Map<String, ColumnProfile> profile(List<ColumnSpec> specs, ProfileOptions options) {
        TableProfile result = TableProfiler.profile(connection, "generic", null, "t_customer", specs, options);
        return result.columns().stream()
                .collect(Collectors.toMap(ColumnProfile::columnName, c -> c));
    }

    @Test
    void rowCountIsExact() {
        TableProfile result = TableProfiler.profile(connection, "generic", null, "t_customer",
                List.of(new ColumnSpec("id")), ProfileOptions.defaults());
        assertTrue(result.rowCountAvailable());
        assertEquals(5, result.rowCount());
    }

    @Test
    void nullRatioAndMinMaxAreCorrect() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("note"), new ColumnSpec("id")), ProfileOptions.defaults());

        ColumnProfile note = columns.get("note");
        assertFalse(note.skipped());
        assertEquals(5, note.rowsSampled());
        assertEquals(3, note.nonNullCount());
        assertEquals(0.4, note.nullRatio(), 1e-9); // 2/5 为空

        ColumnProfile id = columns.get("id");
        assertEquals("1", id.minValue());
        assertEquals("5", id.maxValue());
    }

    @Test
    void lowCardinalityColumnListsEnumValues() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("status")), ProfileOptions.defaults());

        ColumnProfile status = columns.get("status");
        assertTrue(status.lowCardinality());
        assertEquals(2, status.distinctCount());
        assertEquals(asSet("active", "inactive"), asSet(status.enumValues()));
    }

    @Test
    void highCardinalityColumnOnlyGivesCount() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("name")), new ProfileOptions(10_000, /* threshold */ 2, 30, true));

        ColumnProfile name = columns.get("name");
        assertFalse(name.lowCardinality());
        assertEquals(5, name.distinctCount());
        assertTrue(name.enumValues().isEmpty());
    }

    @Test
    void sensitiveColumnNeverListsValuesEvenWhenLowCardinality() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("phone", SemanticType.phone)), ProfileOptions.defaults());

        ColumnProfile phone = columns.get("phone");
        assertTrue(phone.lowCardinality(), "phone 只有 2 个取值，基数判断应该照常生效");
        assertEquals(2, phone.distinctCount(), "统计量不受敏感标记影响");
        assertTrue(phone.enumValues().isEmpty(), "敏感列不能列出实际取值");
    }

    @Test
    void stringColumnGetsLengthStats() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("status")), ProfileOptions.defaults());
        ColumnProfile.LengthStats lengthStats = columns.get("status").lengthStats();
        assertNotNull(lengthStats);
        // "active" 长度 6，"inactive" 长度 8
        assertEquals(6, lengthStats.minLength());
        assertEquals(8, lengthStats.maxLength());
    }

    @Test
    void failingColumnIsSkippedWithReadableReasonAndOthersStillComplete() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("does_not_exist"), new ColumnSpec("status")), ProfileOptions.defaults());

        ColumnProfile bad = columns.get("does_not_exist");
        assertTrue(bad.skipped());
        assertNotNull(bad.skipReason());
        assertFalse(bad.skipReason().isBlank());

        ColumnProfile ok = columns.get("status");
        assertFalse(ok.skipped(), "前一列失败不应该拖累同一批次里的其他列");
        assertEquals(2, ok.distinctCount());
    }

    @Test
    void sampleSizeCapsRowsExamined() {
        // 采样上限 2，即便表里有 5 行，聚合统计也只应该基于 2 行。
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("id")), new ProfileOptions(2, 50, 30, true));
        assertEquals(2, columns.get("id").rowsSampled());
    }

    @Test
    void sampleSizeZeroMeansFullScan() {
        Map<String, ColumnProfile> columns = profile(
                List.of(new ColumnSpec("id")), new ProfileOptions(0, 50, 30, true));
        assertEquals(5, columns.get("id").rowsSampled());
    }

    @Test
    void mysqlSampledFromUsesBacktickQuotingAndLimit() {
        DatabaseStrategy mysql = DatabaseStrategies.resolve("mysql");
        String sql = TableProfiler.sampledFrom(mysql, "mysql", null, "order", mysql.quoteIdentifier("select"), 100);
        assertTrue(sql.contains("`order`"), sql);
        assertTrue(sql.contains("`select`"), sql);
        assertTrue(sql.contains("LIMIT 100"), sql);
    }

    @Test
    void oracleSampledFromUsesFetchFirst() {
        DatabaseStrategy oracle = DatabaseStrategies.resolve("oracle");
        String sql = TableProfiler.sampledFrom(oracle, "oracle", "SCHEMA1", "T1", "\"COL1\"", 500);
        assertTrue(sql.contains("FETCH FIRST 500 ROWS ONLY"), sql);
        assertFalse(sql.contains("LIMIT"), sql);
    }

    private static Set<String> asSet(String... values) {
        return Set.of(values);
    }

    private static Set<String> asSet(List<String> values) {
        return Set.copyOf(values);
    }
}
