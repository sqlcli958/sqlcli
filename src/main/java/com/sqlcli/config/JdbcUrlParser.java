package com.sqlcli.config;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 JDBC URL 推断数据库类型
 */
public final class JdbcUrlParser {
    private static final Pattern ORACLE_SERVICE_PATTERN =
            Pattern.compile("^jdbc:oracle:thin:@//[^/]+/(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORACLE_SID_PATTERN =
            Pattern.compile("^jdbc:oracle:thin:@[^:]+:\\d+:([^/?]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(\\b(?:password|passwd|pwd|token|access[_-]?token|auth[_-]?token|"
                    + "api[_-]?key|private[_-]?key|secret|secretkey|secret[_-]?key|"
                    + "client[_-]?secret|clientSecret)\\b\\s*[=:]\\s*)"
                    + "(?:\"[^\"]*\"|'[^']*'|[^&;,\\s}\\]]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_INFO_PATTERN = Pattern.compile(
            "(jdbc:[a-z0-9:]+://[^\\s/:@]+:)[^@/\\s]+@",
            Pattern.CASE_INSENSITIVE);

    private JdbcUrlParser() {
    }

    public static String redactSecrets(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = SECRET_ASSIGNMENT_PATTERN.matcher(value).replaceAll("$1***");
        return USER_INFO_PATTERN.matcher(redacted).replaceAll("$1***@");
    }

    /** Normalize supported legacy ClickHouse URL forms to the canonical jdbc:clickhouse:// form. */
    public static String normalize(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return jdbcUrl;
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:ch:http://")) {
            return "jdbc:clickhouse://" + jdbcUrl.substring("jdbc:ch:http://".length());
        }
        if (lower.startsWith("jdbc:clickhouse:http://")) {
            return "jdbc:clickhouse://" + jdbcUrl.substring("jdbc:clickhouse:http://".length());
        }
        if (lower.startsWith("jdbc:ch:https://")) {
            return addSslParameter("jdbc:clickhouse://" + jdbcUrl.substring("jdbc:ch:https://".length()));
        }
        if (lower.startsWith("jdbc:clickhouse:https://")) {
            return addSslParameter("jdbc:clickhouse://" + jdbcUrl.substring("jdbc:clickhouse:https://".length()));
        }
        return jdbcUrl;
    }

    private static String addSslParameter(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.matches(".*[?&]ssl=[^&]*.*")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "ssl=true";
    }

    public static String inferDbType(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:ch:")) {
            return "clickhouse";
        }
        if (lower.startsWith("jdbc:clickhouse:")) {
            return "clickhouse";
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return "mysql";
        }
        if (lower.startsWith("jdbc:oracle:")) {
            return "oracle";
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        if (lower.startsWith("jdbc:sqlite:")) {
            return "sqlite";
        }
        return null;
    }

    /** Extract the path database from host-based JDBC URLs such as ClickHouse URLs. */
    public static String extractPathDatabase(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String normalized = normalize(jdbcUrl);
        int authority = normalized.indexOf("://");
        if (authority < 0) {
            return null;
        }
        int pathStart = normalized.indexOf('/', authority + 3);
        if (pathStart < 0 || pathStart == normalized.length() - 1) {
            return null;
        }
        int queryStart = normalized.indexOf('?', pathStart + 1);
        String database = normalized.substring(pathStart + 1,
                queryStart < 0 ? normalized.length() : queryStart);
        return database.isBlank() ? null : database;
    }

    public static String inferOracleConnectMode(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "unknown";
        }
        if (!jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:oracle:thin:@")) {
            return "unknown";
        }
        if (ORACLE_SERVICE_PATTERN.matcher(jdbcUrl).matches()) {
            return "service_name";
        }
        if (ORACLE_SID_PATTERN.matcher(jdbcUrl).matches()) {
            return "sid";
        }
        return "descriptor_or_tns";
    }

    public static String extractOracleDatabaseToken(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "";
        }
        Matcher serviceMatcher = ORACLE_SERVICE_PATTERN.matcher(jdbcUrl);
        if (serviceMatcher.matches()) {
            return serviceMatcher.group(1);
        }
        Matcher sidMatcher = ORACLE_SID_PATTERN.matcher(jdbcUrl);
        if (sidMatcher.matches()) {
            return sidMatcher.group(1);
        }
        return "";
    }
}
