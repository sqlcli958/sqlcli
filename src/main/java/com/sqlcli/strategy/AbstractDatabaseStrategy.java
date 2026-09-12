package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.WorkspaceMetadataExtractor;
import com.sqlcli.graph.workspace.WorkspaceMetadataProvider;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSetMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 数据库策略基类，封装公共默认行为
 */
public abstract class AbstractDatabaseStrategy implements DatabaseStrategy {

    /**
     * 子类必须实现，返回此数据库类型的能力描述
     */
    @Override
    public abstract DatabaseCapabilities capabilities();

    /**
     * 子类必须实现，返回此数据库类型的SQL执行策略
     */
    @Override
    public abstract SqlExecutionPolicy executionPolicy();

    @Override
    public String quoteIdentifier(String identifier) {
        String quote = capabilities().getIdentifierQuote();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        String escaped = identifier.replace(quote, quote + quote);
        return quote + escaped + quote;
    }

    @Override
    public String qualifyTableName(String schemaName, String tableName) {
        if (schemaName != null && !schemaName.isBlank()) {
            return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
        }
        return quoteIdentifier(tableName);
    }

    @Override
    public WorkspaceMetadataProvider createMetadataProvider(Connection conn, DatabaseConfig config) throws SQLException {
        return new WorkspaceMetadataExtractor(conn, config.getAliasName());
    }

    @Override
    public void applyConnectionProperties(DatabaseConfig config, Properties properties) {
        // default no-op
    }

    @Override
    public String preprocessSql(DatabaseConfig config, String sql) {
        String normalized = stripTrailingSemicolon(sql);
        if (!isSelectQuery(normalized)) {
            return normalized;
        }

        int defaultLimit = config.getDefaultQueryLimit();
        if (defaultLimit <= 0 || hasExplicitLimit(normalized)) {
            return normalized;
        }
        return applyDefaultLimit(normalized, defaultLimit);
    }

    @Override
    public List<String> buildConnectionHints(DatabaseConfig config, SQLException exception, Throwable rootCause) {
        List<String> hints = new ArrayList<>();
        String message = lower(exception == null ? null : exception.getMessage());
        String rootMessage = lower(rootCause == null ? null : rootCause.getMessage());

        if (message.contains("no suitable driver") || rootMessage.contains("classnotfound")) {
            hints.add("Driver class/jar may be misconfigured");
            hints.add("Check config/drivers.yaml driverRef and jar path");
        }
        if (rootMessage.contains("connection refused") || rootMessage.contains("operation not permitted")) {
            hints.add("Check whether the database host and port are reachable from this machine");
            hints.add("Check firewall, security group, VPN, or local network restrictions");
        }
        if (message.contains("timed out") || rootMessage.contains("timed out")) {
            hints.add("The database did not respond before the connection timeout");
            hints.add("Try increasing connectionTimeoutMs if the network is slow");
        }
        if (hints.isEmpty()) {
            hints.add("Check jdbcUrl, username, password, driver jar, and network connectivity");
        }
        return hints;
    }

    @Override
    public String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException {
        throw new SQLException("getTableDdl is not implemented for database type: " + type());
    }

    @Override
    public List<TableInfo> listTables(Connection conn, String schemaName, String pattern) throws SQLException {
        // 默认使用 JDBC DatabaseMetaData 获取表列表
        List<TableInfo> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        String tablePattern = pattern != null && !pattern.isBlank() ? pattern : "%";
        String schemaPattern = schemaName != null && !schemaName.isBlank() ? schemaName : null;

        try (ResultSet rs = metaData.getTables(null, schemaPattern, tablePattern, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                String remarks = rs.getString("REMARKS");
                tables.add(new TableInfo(tableName, tableSchema, tableType, remarks));
            }
        }
        return tables;
    }

    @Override
    public Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException {
        Map<String, String> info = new LinkedHashMap<>();
        DatabaseMetaData metaData = conn.getMetaData();
        putIfNotBlank(info, "productName", metaData.getDatabaseProductName());
        putIfNotBlank(info, "productVersion", metaData.getDatabaseProductVersion());
        putIfNotBlank(info, "driverName", metaData.getDriverName());
        putIfNotBlank(info, "driverVersion", metaData.getDriverVersion());
        info.put("jdbcVersion", metaData.getJDBCMajorVersion() + "." + metaData.getJDBCMinorVersion());
        putIfNotBlank(info, "identifierQuote", capabilities().getIdentifierQuote());
        info.put("usesSchemaAsDatabase", String.valueOf(capabilities().isUsesSchemaAsDatabase()));
        info.put("usesCatalogAsDatabase", String.valueOf(capabilities().isUsesCatalogAsDatabase()));
        info.put("supportsDdl", String.valueOf(capabilities().isSupportsDdl()));
        info.put("supportsShow", String.valueOf(capabilities().isSupportsShow()));
        info.put("supportsTransactions", String.valueOf(capabilities().isSupportsTransactions()));
        putIfNotBlank(info, "currentDatabase", conn.getCatalog());
        putIfNotBlank(info, "currentSchema", conn.getSchema());
        return info;
    }

    protected String applyDefaultLimit(String sql, int defaultLimit) {
        return sql + " LIMIT " + defaultLimit;
    }

    protected String stripTrailingSemicolon(String sql) {
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    protected boolean isSelectQuery(String sql) {
        String trimmed = sql.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("select") || trimmed.startsWith("with");
    }

    protected boolean hasExplicitLimit(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        return lower.contains(" limit ")
                || lower.matches("(?s).*\\blimit\\s+\\d+.*")
                || lower.contains(" fetch first ")
                || lower.contains(" fetch next ")
                || lower.contains(" rownum ")
                || lower.contains(" row_number(");
    }

    protected String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    protected Map<String, String> querySingleRow(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                return Map.of();
            }
            ResultSetMetaData metaData = rs.getMetaData();
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                row.put(metaData.getColumnLabel(i), stringify(rs.getObject(i)));
            }
            return row;
        }
    }

    protected void putIfNotBlank(Map<String, String> info, String key, String value) {
        if (value != null && !value.isBlank()) {
            info.put(key, value);
        }
    }

    protected void putAllNonBlank(Map<String, String> target, Map<String, String> source) {
        source.forEach((key, value) -> putIfNotBlank(target, key, value));
    }

    protected String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
