package com.sqlcli.connection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 表行数采集：Web UI 的 /api/tables/row-count 与 CLI 的
 * {@code schema describe --refresh-row-count} 共用同一条 SQL 与同一套标识符白名单。
 */
public final class TableRowCounter {

    private static final int QUERY_TIMEOUT_SECONDS = 60;

    private TableRowCounter() {
    }

    /** 对目标表跑一次 COUNT(*)。 */
    public static long count(Connection connection, String dbType, String schema, String table)
            throws SQLException {
        String qualified = qualifiedName(dbType, schema, table);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * 拼出带引号的 schema.table，标识符走白名单——这两个值要直接进 SQL，杜绝注入面。
     *
     * @throws IllegalArgumentException 标识符不合法
     */
    public static String qualifiedName(String dbType, String schema, String table) {
        if (!isPlainIdentifier(schema) || !isPlainIdentifier(table)) {
            throw new IllegalArgumentException("invalid schema or table name");
        }
        return quote(dbType, schema) + "." + quote(dbType, table);
    }

    private static boolean isPlainIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_$-]{1,128}");
    }

    /** MySQL 只有开了 ANSI_QUOTES 才认双引号，默认要用反引号；其余数据库用 SQL 标准双引号。 */
    private static String quote(String dbType, String value) {
        return "mysql".equalsIgnoreCase(dbType) ? "`" + value + "`" : "\"" + value + "\"";
    }
}
