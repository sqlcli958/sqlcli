package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.config.JdbcUrlParser;
import com.sqlcli.graph.workspace.WorkspaceMetadataProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * ClickHouse 方言策略
 */
public class ClickHouseDatabaseStrategy extends AbstractDatabaseStrategy {

    private static final Pattern LIMIT_BY_PATTERN = Pattern.compile(
            "(?i)\\bLIMIT\\s+\\d+\\s+BY\\b");

    public ClickHouseDatabaseStrategy() {
    }

    @Override
    public String type() {
        return "clickhouse";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.CLICKHOUSE_DEFAULTS();
    }

    @Override
    public SqlExecutionPolicy executionPolicy() {
        return SqlExecutionPolicy.CLICKHOUSE_POLICY;
    }

    @Override
    public String defaultSchema(DatabaseConfig config) {
        if (config.getDatabase() != null && !config.getDatabase().isBlank()) {
            return config.getDatabase();
        }
        String databaseFromUrl = JdbcUrlParser.extractPathDatabase(config.getJdbcUrl());
        return databaseFromUrl != null ? databaseFromUrl : capabilities().getDefaultDatabase();
    }

    @Override
    public String buildJdbcUrl(DatabaseConfig config) {
        if (config.getJdbcUrl() != null && !config.getJdbcUrl().isBlank()) {
            return JdbcUrlParser.normalize(config.getJdbcUrl());
        }

        String host = config.getHost() != null && !config.getHost().isBlank()
                ? config.getHost()
                : "localhost";
        int port = config.getPort() > 0
                ? config.getPort()
                : capabilities().getDefaultPort();
        String database = config.getDatabase() != null && !config.getDatabase().isBlank()
                ? config.getDatabase()
                : capabilities().getDefaultDatabase();

        return String.format("jdbc:clickhouse://%s:%d/%s", host, port, database);
    }

    @Override
    public void applyConnectionProperties(DatabaseConfig config, Properties properties) {
        String version = resolveVersion();
        properties.putIfAbsent("client_name", "sql-cli/" + version);

        Map<String, String> params = config.getParams();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    properties.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Resolve the application version from the package manifest or fallback to a constant.
     */
    private String resolveVersion() {
        try {
            String v = getClass().getPackage().getImplementationVersion();
            if (v != null && !v.isBlank()) {
                return v;
            }
        } catch (Exception ignored) {
        }
        return "1.0.2";
    }

    @Override
    public String preprocessSql(DatabaseConfig config, String sql) {
        String normalized = stripTrailingSemicolon(sql);

        if (isShowOrDescribe(normalized)) {
            return normalized;
        }

        if (!isSelectQuery(normalized)) {
            return normalized;
        }

        int defaultLimit = config.getDefaultQueryLimit();
        if (defaultLimit <= 0 || hasExplicitLimit(normalized) || hasFormatClause(normalized)) {
            return normalized;
        }

        // CH-P2-003: If SETTINGS clause is present, insert LIMIT before SETTINGS
        if (hasSettingsClause(normalized)) {
            return applyDefaultLimitBeforeSettings(normalized, defaultLimit);
        }

        return applyDefaultLimit(normalized, defaultLimit);
    }

    @Override
    protected boolean hasExplicitLimit(String sql) {
        if (super.hasExplicitLimit(sql)) {
            return true;
        }
        return hasLimitBy(sql);
    }

    @Override
    public String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException {
        String effectiveSchema = schemaName != null && !schemaName.isBlank()
                ? schemaName
                : resolveDefaultDatabase(conn, null);
        String qualifiedName = qualifyTableName(effectiveSchema, tableName);

        String sql = "SHOW CREATE TABLE " + qualifiedName;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
            throw new SQLException("Table not found: " + qualifiedName);
        }
    }

    @Override
    public List<TableInfo> listTables(Connection conn, String schemaName, String pattern) throws SQLException {
        String database = schemaName != null && !schemaName.isBlank()
                ? schemaName
                : resolveDefaultDatabase(conn, "default");

        StringBuilder sql = new StringBuilder(
                "SELECT name, database, engine, comment FROM system.tables WHERE database = ?");
        if (pattern != null && !pattern.isBlank()) {
            sql.append(" AND name LIKE ?");
        }
        sql.append(" ORDER BY name");

        List<TableInfo> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, database);
            if (pattern != null && !pattern.isBlank()) {
                ps.setString(2, pattern);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String db = rs.getString("database");
                    String engine = rs.getString("engine");
                    String comment = rs.getString("comment");
                    String type = mapEngineToType(engine);

                    tables.add(new TableInfo(name, db, type, comment));
                }
            }
        }
        return tables;
    }

    @Override
    public List<String> buildConnectionHints(DatabaseConfig config, SQLException exception, Throwable rootCause) {
        List<String> hints = new ArrayList<>();
        String message = lower(exception == null ? null : exception.getMessage());
        String rootMessage = lower(rootCause == null ? null : rootCause.getMessage());

        if (rootMessage.contains("connection refused") || message.contains("connection refused")) {
            hints.add("ClickHouse HTTP port is 8123 (not 9000 which is the native TCP port)");
            hints.add("Ensure the ClickHouse HTTP interface is enabled and reachable on port 8123");
        }
        if (message.contains("ssl") || message.contains("https")
                || rootMessage.contains("ssl") || rootMessage.contains("https")) {
            hints.add("For secure connections, use jdbc:clickhouse://host:8443/database with ssl=true");
            hints.add("Set port to 8443 for ClickHouse HTTPS interface");
        }
        if (message.contains("authentication") || message.contains("401") || message.contains("403")
                || rootMessage.contains("authentication") || rootMessage.contains("401") || rootMessage.contains("403")) {
            hints.add("Check username and password for ClickHouse");
            hints.add("Default ClickHouse user is 'default' with empty password if not configured");
        }
        if (message.contains("timeout") || message.contains("timed out")
                || rootMessage.contains("timeout") || rootMessage.contains("timed out")) {
            hints.add("ClickHouse query or connection timed out");
            hints.add("Try increasing socket_timeout and connection_timeout in params");
        }
        if (message.contains("unknown database") || rootMessage.contains("unknown database")) {
            hints.add("The specified database does not exist in ClickHouse");
            hints.add("Use 'default' as the database name if unsure, or list databases with 'SHOW DATABASES'");
        }

        // Fallback to parent hints
        List<String> parentHints = super.buildConnectionHints(config, exception, rootCause);
        for (String hint : parentHints) {
            if (!hints.contains(hint)) {
                hints.add(hint);
            }
        }

        return hints;
    }

    @Override
    public WorkspaceMetadataProvider createMetadataProvider(Connection conn, DatabaseConfig config) throws SQLException {
        return new ClickHouseWorkspaceMetadataProvider(conn, config);
    }

    @Override
    public Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException {
        Map<String, String> info = new LinkedHashMap<>(super.collectConnectionInfo(conn, config));
        putAllNonBlank(info, querySingleRow(conn, """
                SELECT
                    currentDatabase() AS currentDatabase,
                    currentDatabase() AS currentSchema,
                    currentUser() AS currentUser,
                    currentUser() AS sessionUser,
                    hostName() AS serverHost,
                    version() AS productVersion,
                    timezone() AS serverTimeZone,
                    timezone() AS sessionTimeZone,
                    now() AS currentTimestamp
                """));
        putIfNotBlank(info, "readonly", querySetting(conn, "readonly"));
        putIfNotBlank(info, "maxExecutionTime", querySetting(conn, "max_execution_time"));
        info.put("sampleLimitSql", "SELECT * FROM `table_name` LIMIT 100");
        return info;
    }

    // ---- Helper methods ----

    private String querySetting(Connection conn, String name) throws SQLException {
        Map<String, String> row = querySingleRow(conn,
                "SELECT value FROM system.settings WHERE name = '" + name.replace("'", "''") + "'");
        return row.values().stream().findFirst().orElse("");
    }

    /**
     * Check if SQL is a SHOW, DESC, DESCRIBE, or EXPLAIN statement
     */
    private boolean isShowOrDescribe(String sql) {
        String trimmed = sql.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("show ")
                || trimmed.startsWith("desc ")
                || trimmed.startsWith("describe ")
                || trimmed.startsWith("explain ");
    }

    /**
     * Check for FORMAT clause at statement level (not inside strings/subqueries).
     * Uses a simple heuristic: find the last occurrence of " FORMAT " that appears
     * after all closing parens are balanced at the top level.
     */
    private boolean hasFormatClause(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        char stringDelim = 0;

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);

            // Track string literals
            if (!inString && (c == '\'' || c == '"')) {
                inString = true;
                stringDelim = c;
                continue;
            }
            if (inString) {
                if (c == stringDelim) {
                    // Check for escaped quote
                    if (i + 1 < lower.length() && lower.charAt(i + 1) == stringDelim) {
                        i++; // skip escaped quote
                    } else {
                        inString = false;
                    }
                }
                continue;
            }

            // Track paren depth
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }

            // Only detect FORMAT at top level (depth == 0)
            if (depth == 0 && i > 0 && i + 7 <= lower.length()) {
                // Check for " FORMAT " as a word boundary
                if (lower.charAt(i) == ' '
                        && lower.substring(i + 1, Math.min(i + 8, lower.length())).startsWith("format")
                        && (i + 7 >= lower.length() || !Character.isLetterOrDigit(lower.charAt(i + 7)))) {
                    // Verify preceding char is space (word boundary before FORMAT)
                    if (i + 7 < lower.length() && lower.charAt(i + 7) == ' ') {
                        return true;
                    }
                    // FORMAT at end of string
                    if (i + 7 >= lower.length()) {
                        return true;
                    }
                    // FORMAT followed by non-alphanumeric (e.g., FORMAT\n)
                    if (i + 7 < lower.length() && !Character.isLetterOrDigit(lower.charAt(i + 7))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check for SETTINGS clause at statement level (not inside strings/subqueries).
     * ClickHouse syntax: SELECT ... SETTINGS key=value
     * When SETTINGS is present, LIMIT must come before SETTINGS.
     */
    private boolean hasSettingsClause(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        char stringDelim = 0;

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);

            // Track string literals
            if (!inString && (c == '\'' || c == '"')) {
                inString = true;
                stringDelim = c;
                continue;
            }
            if (inString) {
                if (c == stringDelim) {
                    if (i + 1 < lower.length() && lower.charAt(i + 1) == stringDelim) {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }

            // Track paren depth
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }

            // Only detect SETTINGS at top level (depth == 0)
            if (depth == 0 && i > 0 && i + 9 <= lower.length()) {
                if (lower.charAt(i) == ' '
                        && lower.substring(i + 1, Math.min(i + 9, lower.length())).startsWith("settings")
                        && (i + 9 >= lower.length() || !Character.isLetterOrDigit(lower.charAt(i + 9)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Insert LIMIT before the SETTINGS clause.
     * ClickHouse syntax requires: SELECT ... LIMIT N SETTINGS ...
     */
    private String applyDefaultLimitBeforeSettings(String sql, int defaultLimit) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        char stringDelim = 0;

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);

            if (!inString && (c == '\'' || c == '"')) {
                inString = true;
                stringDelim = c;
                continue;
            }
            if (inString) {
                if (c == stringDelim) {
                    if (i + 1 < lower.length() && lower.charAt(i + 1) == stringDelim) {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }

            if (depth == 0 && i > 0 && i + 9 <= lower.length()) {
                if (lower.charAt(i) == ' '
                        && lower.substring(i + 1, Math.min(i + 9, lower.length())).startsWith("settings")
                        && (i + 9 >= lower.length() || !Character.isLetterOrDigit(lower.charAt(i + 9)))) {
                    // Insert " LIMIT N" before the space that precedes SETTINGS
                    return sql.substring(0, i) + " LIMIT " + defaultLimit + sql.substring(i);
                }
            }
        }
        // Fallback: should not reach here if hasSettingsClause returned true
        return sql + " LIMIT " + defaultLimit;
    }

    /**
     * Check for ClickHouse-specific LIMIT BY pattern
     */
    private boolean hasLimitBy(String sql) {
        return LIMIT_BY_PATTERN.matcher(sql).find();
    }

    /**
     * Map ClickHouse engine name to table type
     */
    private String mapEngineToType(String engine) {
        if (engine == null || engine.isBlank()) {
            return "TABLE";
        }
        String lower = engine.toLowerCase(Locale.ROOT);
        if (lower.contains("mergetree") || lower.contains("replicatedmergetree")
                || lower.contains("replacingmergetree") || lower.contains("summingmergetree")
                || lower.contains("aggregatingmergetree") || lower.contains("collapsingmergetree")
                || lower.contains("versionedcollapsingmergetree")
                || lower.equals("dictionary")) {
            return "TABLE";
        }
        if (lower.equals("view")) {
            return "VIEW";
        }
        if (lower.equals("materializedview") || lower.contains("materializedview")) {
            return "VIEW";
        }
        return "TABLE";
    }

    /**
     * Resolve the default database name from config or connection catalog
     */
    private String resolveDefaultDatabase(Connection conn, String fallback) {
        try {
            if (conn != null) {
                String catalog = conn.getCatalog();
                if (catalog != null && !catalog.isBlank()) {
                    return catalog;
                }
            }
        } catch (SQLException ignored) {
        }
        return fallback != null ? fallback : "default";
    }
}
