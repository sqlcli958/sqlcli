package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Oracle 方言策略
 */
public class OracleDatabaseStrategy extends AbstractDatabaseStrategy {
    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.ORACLE_DEFAULTS();
    }

    @Override
    public SqlExecutionPolicy executionPolicy() {
        return SqlExecutionPolicy.STANDARD_RDBMS_POLICY;
    }

    @Override
    public String type() {
        return "oracle";
    }

    @Override
    public String defaultSchema(DatabaseConfig config) {
        return config.getUsername();
    }

    @Override
    public String buildJdbcUrl(DatabaseConfig config) {
        // 如果已经有 jdbcUrl，从中解析 host/port/serviceName/sid
        if (config.getJdbcUrl() != null && !config.getJdbcUrl().isBlank()) {
            parseJdbcUrlToConfig(config);
            return config.getJdbcUrl();
        }

        if (config.getServiceName() != null && !config.getServiceName().isBlank()) {
            return String.format("jdbc:oracle:thin:@//%s:%d/%s", config.getHost(), config.getPort(), config.getServiceName());
        }
        if (config.getSid() != null && !config.getSid().isBlank()) {
            return String.format("jdbc:oracle:thin:@%s:%d:%s", config.getHost(), config.getPort(), config.getSid());
        }
        throw new IllegalArgumentException("Oracle config requires jdbcUrl, serviceName or sid");
    }

    /**
     * 从 jdbcUrl 解析 host, port, serviceName, sid
     */
    private void parseJdbcUrlToConfig(DatabaseConfig config) {
        String jdbcUrl = config.getJdbcUrl();
        String mode = JdbcUrlParser.inferOracleConnectMode(jdbcUrl);
        String token = JdbcUrlParser.extractOracleDatabaseToken(jdbcUrl);

        // 解析 host 和 port
        // SERVICE_NAME 格式: jdbc:oracle:thin:@//host:port/serviceName
        // SID 格式: jdbc:oracle:thin:@host:port:sid
        if ("service_name".equals(mode)) {
            config.setServiceName(token);
            parseHostPortFromServiceUrl(jdbcUrl, config);
        } else if ("sid".equals(mode)) {
            config.setSid(token);
            parseHostPortFromSidUrl(jdbcUrl, config);
        }
    }

    private void parseHostPortFromServiceUrl(String jdbcUrl, DatabaseConfig config) {
        // jdbc:oracle:thin:@//host:port/serviceName
        String afterAt = jdbcUrl.substring(jdbcUrl.indexOf("@//") + 3);
        String hostPort = afterAt.substring(0, afterAt.indexOf("/"));
        String[] parts = hostPort.split(":");
        if (parts.length >= 1) config.setHost(parts[0]);
        if (parts.length >= 2) config.setPort(Integer.parseInt(parts[1]));
    }

    private void parseHostPortFromSidUrl(String jdbcUrl, DatabaseConfig config) {
        // jdbc:oracle:thin:@host:port:sid
        String afterAt = jdbcUrl.substring(jdbcUrl.indexOf("@") + 1);
        String[] parts = afterAt.split(":");
        if (parts.length >= 1) config.setHost(parts[0]);
        if (parts.length >= 2) config.setPort(Integer.parseInt(parts[1]));
    }

    @Override
    public void applyConnectionProperties(DatabaseConfig config, Properties properties) {
        System.setProperty("oracle.jdbc.javaNetNio", "false");
        System.setProperty("oracle.net.disableOob", "true");
        properties.putIfAbsent("oracle.jdbc.javaNetNio", "false");
        properties.putIfAbsent("oracle.net.disableOob", "true");
    }

    @Override
    protected String applyDefaultLimit(String sql, int defaultLimit) {
        return "SELECT * FROM (" + sql + ") SQLCLI_LIMITED WHERE ROWNUM <= " + defaultLimit;
    }

    @Override
    public String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException {
        // Oracle 使用 DBMS_METADATA.GET_DDL
        String owner = schemaName != null && !schemaName.isBlank() ? schemaName : getCurrentSchema(conn);
        String escapedTable = escapeName(tableName);
        String escapedOwner = escapeName(owner);

        // 1. 获取表 DDL
        String tableDdl = getTableDdlCore(conn, escapedTable, escapedOwner);

        // 2. 获取列注释
        StringBuilder sb = new StringBuilder(tableDdl);
        String commentsSql = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = '" + escapedOwner + "' AND TABLE_NAME = '" + escapedTable + "' AND COMMENTS IS NOT NULL";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(commentsSql)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String comment = rs.getString("COMMENTS");
                if (comment != null && !comment.isBlank()) {
                    sb.append("\n\nCOMMENT ON COLUMN \"").append(owner).append("\".\"").append(tableName)
                            .append("\".\"").append(columnName).append("\" IS '")
                            .append(escapeComment(comment)).append("';");
                }
            }
        }

        return sb.toString();
    }

    private String getTableDdlCore(Connection conn, String escapedTable, String escapedOwner) throws SQLException {
        String sql = "SELECT DBMS_METADATA.GET_DDL('TABLE', '" + escapedTable + "', '" + escapedOwner + "') AS DDL FROM DUAL";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                // Oracle DDL 可能是 CLOB 类型
                return rs.getString(1);
            }
            throw new SQLException("Table not found in schema: " + escapedOwner);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toUpperCase().contains("ORA-")) {
                throw new SQLException("Failed to get DDL: " + e.getMessage());
            }
            throw e;
        }
    }

    private String escapeComment(String comment) {
        // 转义单引号
        return comment.replace("'", "''");
    }

    private String getCurrentSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    private String escapeName(String name) {
        // Oracle 标识符需要转义单引号
        return name.replace("'", "''");
    }

    @Override
    public List<String> buildConnectionHints(DatabaseConfig config, SQLException exception, Throwable rootCause) {
        List<String> hints = super.buildConnectionHints(config, exception, rootCause);
        String message = lower(exception == null ? null : exception.getMessage());
        String rootMessage = lower(rootCause == null ? null : rootCause.getMessage());
        String url = config.buildJdbcUrl();

        if (rootMessage.contains("minus one") || message.contains("minus one")
                || rootMessage.contains("got minus one from a read call")) {
            hints.add("Oracle connection handshake failed - this often indicates SERVICE_NAME vs SID mismatch");
            hints.add("If using SERVICE_NAME format: jdbc:oracle:thin:@//host:port/serviceName");
            hints.add("If using SID format: jdbc:oracle:thin:@host:port:sid");
            hints.add("Try switching the URL format - your serviceName might actually be a SID");
            appendOracleUrlModeHints(config, hints, url);
        }
        if (message.contains("driver returned null connection for url")) {
            hints.add("Current driver did not accept this JDBC URL format");
            hints.add("For Oracle SID: jdbc:oracle:thin:@host:port:sid");
            hints.add("For Oracle service name: jdbc:oracle:thin:@//host:port/serviceName");
        }
        if (message.contains("timed out") || rootMessage.contains("timed out")) {
            hints.add("For Oracle, timeout usually indicates network/listener reachability, not SID/service-name mismatch");
        }
        if (message.contains("ora-12514") || message.contains("ora-12505")) {
            hints.add("Oracle listener cannot resolve the requested service or SID");
            hints.add("If you are using a service name, prefer jdbc:oracle:thin:@//host:port/serviceName");
            hints.add("If you are using a SID, use jdbc:oracle:thin:@host:port:sid");
        }
        if (message.contains("ora-17800") || rootMessage.contains("ora-17800")) {
            hints.add("Oracle network handshake/read was interrupted (ORA-17800)");
            hints.add("Align URL and driver settings with a known-good client (for example, IntelliJ IDEA)");
            hints.add("Try forcing classic Java socket mode: JAVA_TOOL_OPTIONS='-Doracle.jdbc.javaNetNio=false'");
            hints.add("If still failing, try another Oracle driverRef (oracle11 / oracle19 / exact ojdbc version used by IDEA)");
        }
        if (!rootMessage.contains("minus one")) {
            appendOracleModeSummary(config, hints, url);
        }
        return hints;
    }

    private void appendOracleUrlModeHints(DatabaseConfig config, List<String> hints, String url) {
        String mode = JdbcUrlParser.inferOracleConnectMode(url);
        String token = JdbcUrlParser.extractOracleDatabaseToken(url);
        if (token.isBlank()) {
            return;
        }
        if ("service_name".equals(mode)) {
            hints.add("Your current URL uses SERVICE_NAME mode with token '" + token + "'");
            hints.add("Try SID mode instead: jdbc:oracle:thin:@" + config.getHost() + ":" + config.getPort() + ":" + token);
        } else if ("sid".equals(mode)) {
            hints.add("Your current URL uses SID mode with token '" + token + "'");
            hints.add("Try SERVICE_NAME mode instead: jdbc:oracle:thin:@//" + config.getHost() + ":" + config.getPort() + "/" + token);
        }
    }

    private void appendOracleModeSummary(DatabaseConfig config, List<String> hints, String url) {
        String mode = JdbcUrlParser.inferOracleConnectMode(url);
        String token = JdbcUrlParser.extractOracleDatabaseToken(url);
        if ("sid".equals(mode)) {
            hints.add("Current Oracle URL mode: SID");
            if (!token.isBlank()) {
                hints.add("If '" + token + "' is actually a service name, switch to: jdbc:oracle:thin:@//host:port/" + token);
            }
        } else if ("service_name".equals(mode)) {
            hints.add("Current Oracle URL mode: SERVICE_NAME");
            if (!token.isBlank()) {
                hints.add("If '" + token + "' is actually a SID, switch to: jdbc:oracle:thin:@host:port:" + token);
            }
        } else if ("descriptor_or_tns".equals(mode)) {
            hints.add("Current Oracle URL mode: descriptor/TNS (advanced format)");
        }
    }

    @Override
    public Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException {
        Map<String, String> info = new LinkedHashMap<>(super.collectConnectionInfo(conn, config));
        putAllNonBlank(info, querySingleRow(conn, """
                SELECT
                    SYS_CONTEXT('USERENV', 'DB_NAME') AS currentDatabase,
                    SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS currentSchema,
                    SYS_CONTEXT('USERENV', 'CURRENT_USER') AS currentUser,
                    SYS_CONTEXT('USERENV', 'SESSION_USER') AS sessionUser,
                    SYS_CONTEXT('USERENV', 'SERVER_HOST') AS serverHost,
                    SYS_CONTEXT('USERENV', 'INSTANCE_NAME') AS serverPort,
                    DBTIMEZONE AS serverTimeZone,
                    SESSIONTIMEZONE AS sessionTimeZone,
                    TO_CHAR(CURRENT_TIMESTAMP, 'YYYY-MM-DD HH24:MI:SS TZH:TZM') AS currentTimestamp
                FROM DUAL
                """));
        putIfNotBlank(info, "databaseCharset", querySingleValue(conn,
                "SELECT VALUE FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER = 'NLS_CHARACTERSET'"));
        putIfNotBlank(info, "serverCharset", info.get("databaseCharset"));
        putIfNotBlank(info, "connectionCharset", querySingleValue(conn,
                "SELECT VALUE FROM NLS_SESSION_PARAMETERS WHERE PARAMETER = 'NLS_LANGUAGE'"));
        putIfNotBlank(info, "serverCollation", querySingleValue(conn,
                "SELECT VALUE FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER = 'NLS_SORT'"));
        putIfNotBlank(info, "databaseCollation", info.get("serverCollation"));
        putIfNotBlank(info, "transactionIsolation", stringify(conn.getTransactionIsolation()));
        info.put("oracleConnectMode", JdbcUrlParser.inferOracleConnectMode(config.buildJdbcUrl()));
        info.put("sampleLimitSql", "SELECT * FROM \"TABLE_NAME\" FETCH FIRST 100 ROWS ONLY");
        return info;
    }

    private String querySingleValue(Connection conn, String sql) throws SQLException {
        Map<String, String> row = querySingleRow(conn, sql);
        return row.values().stream().findFirst().orElse("");
    }
}
