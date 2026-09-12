package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;

/**
 * 通用数据库策略
 */
public class GenericDatabaseStrategy extends AbstractDatabaseStrategy {
    @Override
    public String type() {
        return "generic";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.builder()
                .supportsTransactions(true)
                .supportsRecoverySql(true)
                .supportsDdl(true)
                .supportsShow(true)
                .defaultPort(0)
                .identifierQuote("")
                .build();
    }

    @Override
    public SqlExecutionPolicy executionPolicy() {
        return SqlExecutionPolicy.STANDARD_RDBMS_POLICY;
    }

    @Override
    public String buildJdbcUrl(DatabaseConfig config) {
        throw new IllegalArgumentException("Unsupported database type: " + config.getType());
    }
}
