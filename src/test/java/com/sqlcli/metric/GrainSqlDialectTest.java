package com.sqlcli.metric;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 分支：没有 DATE_TRUNC，全靠 date() 的 modifier 语法手算。用真实 sqlite 连接跑一遍
 * 生成的表达式，而不是只断言字符串——手算的偏移量（week/quarter）错了会生成一条能跑但
 * 分桶错误的 SQL，那种错不报警、有结果、数是错的，字符串比对看不出这个错。
 */
class GrainSqlDialectTest {

    @Test
    void supportsDialectIncludesSqlite() {
        assertTrue(GrainSqlDialect.supportsDialect("sqlite"));
        assertTrue(GrainSqlDialect.supportsDialect("SQLite"));
    }

    @Test
    void unsupportedGrainReportsClearly() {
        MetricExpansionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                MetricExpansionException.class, () -> GrainSqlDialect.truncate("sqlite", "col", "hour"));
        assertTrue(ex.getMessage().contains("sqlite"));
    }

    @Test
    void truncationExpressionsMatchExpectedBucketsAgainstRealSqlite() throws SQLException {
        // 2026-08-26 是周三，属于 2026-08-24（周一）那一周、Q3、8 月、2026 年——
        // 用真实值反推，不用再造一遍公式来验证公式。
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = conn.createStatement()) {
            assertBucket(st, "day", "2026-08-26");
            assertBucket(st, "week", "2026-08-24"); // 周一
            assertBucket(st, "month", "2026-08-01");
            assertBucket(st, "quarter", "2026-07-01"); // Q3 = 7,8,9 月
            assertBucket(st, "year", "2026-01-01");
        }
    }

    @Test
    void weekBoundaryIsMondayInclusiveNotOffByOne() throws SQLException {
        // 周一本身应该截断到自己，不是上一周或下一周——这条最容易在取模符号上错一位。
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = conn.createStatement()) {
            assertBucket(st, "week", "2026-08-24", "2026-08-24"); // 周一输入 -> 自己
            assertBucket(st, "week", "2026-08-24", "2026-08-30"); // 周日输入 -> 同一周的周一
        }
    }

    @Test
    void quarterBoundaryCoversAllThreeMonthsOfEachQuarter() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = conn.createStatement()) {
            assertBucket(st, "quarter", "2026-01-01", "2026-01-15"); // Q1
            assertBucket(st, "quarter", "2026-01-01", "2026-03-31");
            assertBucket(st, "quarter", "2026-04-01", "2026-04-01"); // Q2
            assertBucket(st, "quarter", "2026-04-01", "2026-06-30");
            assertBucket(st, "quarter", "2026-10-01", "2026-12-25"); // Q4
        }
    }

    private void assertBucket(Statement st, String grain, String expected) throws SQLException {
        assertBucket(st, grain, expected, "2026-08-26");
    }

    private void assertBucket(Statement st, String grain, String expected, String inputDate) throws SQLException {
        String expr = GrainSqlDialect.truncate("sqlite", "'" + inputDate + "'", grain);
        try (ResultSet rs = st.executeQuery("SELECT " + expr)) {
            assertTrue(rs.next());
            assertEquals(expected, rs.getString(1), "grain=" + grain + " input=" + inputDate + " expr=" + expr);
        }
    }
}
