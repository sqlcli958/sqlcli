package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL 方言策略
 */
public class MySqlDatabaseStrategy extends AbstractDatabaseStrategy {
    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.MySQL_DEFAULTS();
    }

    @Override
    public SqlExecutionPolicy executionPolicy() {
        return SqlExecutionPolicy.STANDARD_RDBMS_POLICY;
    }

    @Override
    public String buildJdbcUrl(DatabaseConfig config) {
        return String.format("jdbc:mysql://%s:%d/%s", config.getHost(), config.getPort(), config.getDatabase());
    }

    @Override
    public String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException {
        // MySQL 使用 SHOW CREATE TABLE
        String sql = "SHOW CREATE TABLE " + quoteIdentifier(tableName);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                // SHOW CREATE TABLE 返回两列: Table, Create Table
                return rs.getString(2);
            }
            throw new SQLException("Table not found: " + tableName);
        }
    }

    @Override
    public Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException {
        Map<String, String> info = new LinkedHashMap<>(super.collectConnectionInfo(conn, config));
        putAllNonBlank(info, querySingleRow(conn, """
                SELECT
                    DATABASE() AS currentDatabase,
                    DATABASE() AS currentSchema,
                    CURRENT_USER() AS currentUser,
                    USER() AS sessionUser,
                    @@hostname AS serverHost,
                    @@port AS serverPort,
                    @@global.time_zone AS serverTimeZone,
                    @@session.time_zone AS sessionTimeZone,
                    NOW() AS currentTimestamp,
                    @@character_set_server AS serverCharset,
                    @@collation_server AS serverCollation,
                    @@character_set_database AS databaseCharset,
                    @@collation_database AS databaseCollation,
                    @@character_set_connection AS connectionCharset,
                    @@sql_mode AS sqlMode,
                    @@transaction_isolation AS transactionIsolation,
                    @@lower_case_table_names AS lowerCaseTableNames,
                    @@version_comment AS versionComment
                """));
        info.put("sampleLimitSql", "SELECT * FROM `table_name` LIMIT 100");
        return info;
    }
}
