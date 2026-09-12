package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL 方言策略
 */
public class PostgreSqlDatabaseStrategy extends AbstractDatabaseStrategy {
    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.POSTGRESQL_DEFAULTS();
    }

    @Override
    public SqlExecutionPolicy executionPolicy() {
        return SqlExecutionPolicy.STANDARD_RDBMS_POLICY;
    }

    @Override
    public String type() {
        return "postgresql";
    }

    @Override
    public String buildJdbcUrl(DatabaseConfig config) {
        return String.format("jdbc:postgresql://%s:%d/%s", config.getHost(), config.getPort(), config.getDatabase());
    }

    @Override
    public void applyConnectionProperties(DatabaseConfig config, Properties properties) {
        // SSL 配置：优先使用别名中显式设置的 ssl 参数，默认 prefer
        String ssl = config.getParams() != null && config.getParams().containsKey("ssl")
                ? config.getParams().get("ssl") : "prefer";
        properties.setProperty("ssl", ssl);
        // 设置登录超时
        properties.setProperty("loginTimeout", "10");
        // 设置连接超时
        properties.setProperty("connectTimeout", "10");
        // 禁用二进制传输（某些情况下可能有问题）
        properties.setProperty("binaryTransfer", "false");
    }

    @Override
    public String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException {
        String schema = schemaName != null && !schemaName.isBlank() ? schemaName : "public";
        String escapedSchema = quoteIdentifier(schema);
        String escapedTable = quoteIdentifier(tableName);

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(escapedSchema).append(".").append(escapedTable).append(" (\n");

        // 获取列信息
        List<ColumnInfo> columns = getColumns(conn, schema, tableName);
        for (int i = 0; i < columns.size(); i++) {
            ColumnInfo col = columns.get(i);
            ddl.append("  ").append(quoteIdentifier(col.name)).append(" ").append(col.type);
            if (!col.nullable) {
                ddl.append(" NOT NULL");
            }
            if (col.defaultValue != null && !col.defaultValue.isEmpty()) {
                ddl.append(" DEFAULT ").append(col.defaultValue);
            }
            if (i < columns.size() - 1) {
                ddl.append(",");
            }
            ddl.append("\n");
        }

        // 获取主键
        String primaryKey = getPrimaryKey(conn, schema, tableName);
        if (primaryKey != null) {
            ddl.append("  , PRIMARY KEY (").append(primaryKey).append(")\n");
        }

        ddl.append(");\n");

        // 获取注释
        List<CommentInfo> comments = getComments(conn, schema, tableName);
        for (CommentInfo comment : comments) {
            if (comment.columnName != null) {
                ddl.append("\nCOMMENT ON COLUMN ").append(escapedSchema).append(".").append(escapedTable)
                        .append(".").append(quoteIdentifier(comment.columnName))
                        .append(" IS '").append(escapeString(comment.comment)).append("';\n");
            } else {
                ddl.append("\nCOMMENT ON TABLE ").append(escapedSchema).append(".").append(escapedTable)
                        .append(" IS '").append(escapeString(comment.comment)).append("';\n");
            }
        }

        // 获取索引
        List<IndexInfo> indexes = getIndexes(conn, schema, tableName);
        for (IndexInfo idx : indexes) {
            if (!idx.isPrimary) {
                ddl.append("\nCREATE ").append(idx.isUnique ? "UNIQUE " : "")
                        .append("INDEX ").append(quoteIdentifier(idx.name))
                        .append(" ON ").append(escapedSchema).append(".").append(escapedTable)
                        .append(" (").append(idx.columns).append(");\n");
            }
        }

        return ddl.toString();
    }

    private List<ColumnInfo> getColumns(Connection conn, String schema, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String sql = """
            SELECT
                a.attname AS column_name,
                pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                NOT a.attnotnull AS nullable,
                pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS default_value
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef d ON (a.attrelid = d.adrelid AND a.attnum = d.adnum)
            JOIN pg_catalog.pg_class c ON a.attrelid = c.oid
            JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relname = ?
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo();
                col.name = rs.getString("column_name");
                col.type = rs.getString("data_type");
                col.nullable = rs.getBoolean("nullable");
                col.defaultValue = rs.getString("default_value");
                columns.add(col);
            }
        }
        return columns;
    }

    private String getPrimaryKey(Connection conn, String schema, String tableName) throws SQLException {
        String sql = """
            SELECT a.attname
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class c ON i.indrelid = c.oid
            JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
            WHERE n.nspname = ? AND c.relname = ? AND i.indisprimary
            ORDER BY array_position(i.indkey, a.attnum)
            """;

        List<String> pkColumns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                pkColumns.add(quoteIdentifier(rs.getString("attname")));
            }
        }
        return pkColumns.isEmpty() ? null : String.join(", ", pkColumns);
    }

    private List<CommentInfo> getComments(Connection conn, String schema, String tableName) throws SQLException {
        List<CommentInfo> comments = new ArrayList<>();

        // 表注释
        String tableCommentSql = """
            SELECT obj_description(c.oid, 'pg_class') AS comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relname = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(tableCommentSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getString("comment") != null) {
                CommentInfo ci = new CommentInfo();
                ci.comment = rs.getString("comment");
                comments.add(ci);
            }
        }

        // 列注释
        String colCommentSql = """
            SELECT a.attname AS column_name, col_description(a.attrelid, a.attnum) AS comment
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON a.attrelid = c.oid
            JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relname = ?
              AND a.attnum > 0 AND NOT a.attisdropped
              AND col_description(a.attrelid, a.attnum) IS NOT NULL
            """;
        try (PreparedStatement ps = conn.prepareStatement(colCommentSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                CommentInfo ci = new CommentInfo();
                ci.columnName = rs.getString("column_name");
                ci.comment = rs.getString("comment");
                comments.add(ci);
            }
        }

        return comments;
    }

    private List<IndexInfo> getIndexes(Connection conn, String schema, String tableName) throws SQLException {
        List<IndexInfo> indexes = new ArrayList<>();
        String sql = """
            SELECT
                i.relname AS index_name,
                ix.indisunique AS is_unique,
                ix.indisprimary AS is_primary,
                array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) AS columns
            FROM pg_catalog.pg_index ix
            JOIN pg_catalog.pg_class i ON ix.indexrelid = i.oid
            JOIN pg_catalog.pg_class t ON ix.indrelid = t.oid
            JOIN pg_catalog.pg_namespace n ON t.relnamespace = n.oid
            JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE n.nspname = ? AND t.relname = ?
            GROUP BY i.relname, ix.indisunique, ix.indisprimary
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                IndexInfo idx = new IndexInfo();
                idx.name = rs.getString("index_name");
                idx.isUnique = rs.getBoolean("is_unique");
                idx.isPrimary = rs.getBoolean("is_primary");

                // 处理数组列
                String[] cols = (String[]) rs.getArray("columns").getArray();
                List<String> quotedCols = new ArrayList<>();
                for (String col : cols) {
                    quotedCols.add(quoteIdentifier(col));
                }
                idx.columns = String.join(", ", quotedCols);

                indexes.add(idx);
            }
        }
        return indexes;
    }

    @Override
    public Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException {
        Map<String, String> info = new LinkedHashMap<>(super.collectConnectionInfo(conn, config));
        putAllNonBlank(info, querySingleRow(conn, """
                SELECT
                    current_database() AS currentDatabase,
                    current_schema() AS currentSchema,
                    current_user AS currentUser,
                    session_user AS sessionUser,
                    inet_server_addr()::text AS serverHost,
                    inet_server_port()::text AS serverPort,
                    current_setting('TimeZone') AS serverTimeZone,
                    current_setting('TimeZone') AS sessionTimeZone,
                    now()::text AS currentTimestamp,
                    current_setting('server_encoding') AS serverCharset,
                    current_setting('lc_collate') AS serverCollation,
                    current_setting('server_encoding') AS databaseCharset,
                    current_setting('lc_collate') AS databaseCollation,
                    current_setting('client_encoding') AS connectionCharset,
                    current_setting('search_path') AS searchPath,
                    current_setting('transaction_isolation') AS transactionIsolation,
                    current_setting('server_version_num') AS serverVersionNum,
                    current_setting('standard_conforming_strings') AS standardConformingStrings
                """));
        info.put("sampleLimitSql", "SELECT * FROM \"table_name\" LIMIT 100");
        return info;
    }

    private String escapeString(String value) {
        return value.replace("'", "''");
    }

    private static class ColumnInfo {
        String name;
        String type;
        boolean nullable;
        String defaultValue;
    }

    private static class CommentInfo {
        String columnName;
        String comment;
    }

    private static class IndexInfo {
        String name;
        boolean isUnique;
        boolean isPrimary;
        String columns;
    }
}
