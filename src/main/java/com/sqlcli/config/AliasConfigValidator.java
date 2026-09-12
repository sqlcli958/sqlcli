package com.sqlcli.config;

import java.util.Locale;

/** Shared validation for aliases created by CLI and Web UI. */
public final class AliasConfigValidator {
    private AliasConfigValidator() {
    }

    public static void validate(DatabaseConfig config) {
        if ("yearning".equalsIgnoreCase(config.getAccessMode())) {
            validateYearning(config);
            return;
        }
        if ("sqlite".equalsIgnoreCase(config.getType())) {
            validateSqlite(config);
            return;
        }
        if (blank(config.getDriverRef())) throw new IllegalArgumentException("driverRef is required");
        if (blank(config.getUsername())) throw new IllegalArgumentException("username is required");
        if (blank(config.getSecretRef())) throw new IllegalArgumentException("secretRef is required");
        if (blank(config.getType())) throw new IllegalArgumentException("dbType could not be inferred from jdbcUrl");
        if (blank(config.getJdbcUrl())) {
            if (("mysql".equalsIgnoreCase(config.getType()) || "clickhouse".equalsIgnoreCase(config.getType()))
                    && blank(config.getDatabase())) {
                throw new IllegalArgumentException("jdbcUrl or database is required for " + config.getType());
            }
            if ("oracle".equalsIgnoreCase(config.getType())
                    && blank(config.getServiceName()) && blank(config.getSid())) {
                throw new IllegalArgumentException("jdbcUrl or serviceName/sid is required for oracle");
            }
        }
        validateDbTypeConsistency(config);
    }

    public static void inferDbType(DatabaseConfig config) {
        if (!blank(config.getType()) || blank(config.getJdbcUrl())) return;
        config.setType(JdbcUrlParser.inferDbType(config.getJdbcUrl()));
    }

    /**
     * sqlite 是文件连接：没有 host/port/账号密码，也不需要 driverRef——驱动内置在 jar 里，
     * 不用像其他库那样在 settings.yaml 里配一份。
     *
     * <p>文件路径认 {@code database}（{@link com.sqlcli.strategy.SqliteDatabaseStrategy#buildJdbcUrl}
     * 读的就是这个字段），也接受手写的完整 {@code jdbc:sqlite:<path>}。两者选一即可——
     * 都不填才拒绝。
     */
    private static void validateSqlite(DatabaseConfig config) {
        boolean hasDatabase = !blank(config.getDatabase());
        boolean hasJdbcUrl = !blank(config.getJdbcUrl());
        if (!hasDatabase && !hasJdbcUrl) {
            throw new IllegalArgumentException("database (sqlite file path) or jdbcUrl is required for sqlite");
        }
        if (hasJdbcUrl && !config.getJdbcUrl().toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:")) {
            throw new IllegalArgumentException("jdbcUrl must start with jdbc:sqlite: for sqlite");
        }
    }

    private static void validateYearning(DatabaseConfig config) {
        if (blank(config.getYearningHost())) throw new IllegalArgumentException("yearningHost is required for accessMode=yearning");
        if (blank(config.getYearningIdc())) throw new IllegalArgumentException("yearningIdc is required for accessMode=yearning");
        if (blank(config.getYearningDatabase())) throw new IllegalArgumentException("yearningDatabase is required for accessMode=yearning");
        if (blank(config.getSecretRef())) throw new IllegalArgumentException("secretRef is required for accessMode=yearning");
    }

    private static void validateDbTypeConsistency(DatabaseConfig config) {
        if (blank(config.getJdbcUrl()) || blank(config.getType())) return;
        String url = config.getJdbcUrl().toLowerCase(Locale.ROOT);
        String type = config.getType().toLowerCase(Locale.ROOT);
        boolean matches = (url.startsWith("jdbc:ch:") || url.startsWith("jdbc:clickhouse:")) && "clickhouse".equals(type)
                || url.startsWith("jdbc:mysql:") && "mysql".equals(type)
                || url.startsWith("jdbc:oracle:") && "oracle".equals(type)
                || url.startsWith("jdbc:postgresql:") && "postgresql".equals(type);
        if (!matches && (url.startsWith("jdbc:ch:") || url.startsWith("jdbc:clickhouse:")
                || url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:oracle:") || url.startsWith("jdbc:postgresql:"))) {
            throw new IllegalArgumentException("dbType does not match jdbcUrl: " + JdbcUrlParser.redactSecrets(config.getJdbcUrl()));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
