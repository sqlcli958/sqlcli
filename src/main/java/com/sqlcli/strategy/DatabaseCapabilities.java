package com.sqlcli.strategy;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DatabaseCapabilities {

    private final boolean supportsTransactions;
    private final boolean supportsRecoverySql;
    private final boolean supportsDdl;
    private final boolean supportsShow;
    private final boolean affectedRowsReliable;
    private final boolean usesSchemaAsDatabase;
    private final boolean usesCatalogAsDatabase;
    private final int defaultPort;
    private final int defaultSecurePort;
    private final String identifierQuote;
    private final String defaultDatabase;

    public static DatabaseCapabilities MySQL_DEFAULTS() {
        return DatabaseCapabilities.builder()
                .supportsTransactions(true)
                .supportsRecoverySql(true)
                .supportsDdl(true)
                .supportsShow(true)
                .affectedRowsReliable(true)
                .usesSchemaAsDatabase(false)
                .usesCatalogAsDatabase(true)
                .defaultPort(3306)
                .defaultSecurePort(3306)
                .identifierQuote("`")
                .defaultDatabase(null)
                .build();
    }

    public static DatabaseCapabilities ORACLE_DEFAULTS() {
        return DatabaseCapabilities.builder()
                .supportsTransactions(true)
                .supportsRecoverySql(true)
                .supportsDdl(true)
                .supportsShow(false)
                .affectedRowsReliable(true)
                .usesSchemaAsDatabase(true)
                .usesCatalogAsDatabase(false)
                .defaultPort(1521)
                .defaultSecurePort(1521)
                .identifierQuote("\"")
                .defaultDatabase(null)
                .build();
    }

    public static DatabaseCapabilities POSTGRESQL_DEFAULTS() {
        return DatabaseCapabilities.builder()
                .supportsTransactions(true)
                .supportsRecoverySql(true)
                .supportsDdl(true)
                .supportsShow(true)
                .affectedRowsReliable(true)
                .usesSchemaAsDatabase(true)
                .usesCatalogAsDatabase(false)
                .defaultPort(5432)
                .defaultSecurePort(5432)
                .identifierQuote("\"")
                .defaultDatabase(null)
                .build();
    }

    public static DatabaseCapabilities CLICKHOUSE_DEFAULTS() {
        return DatabaseCapabilities.builder()
                .supportsTransactions(false)
                .supportsRecoverySql(false)
                .supportsDdl(true)
                .supportsShow(true)
                .affectedRowsReliable(false)
                .usesSchemaAsDatabase(true)
                .usesCatalogAsDatabase(false)
                .defaultPort(8123)
                .defaultSecurePort(8443)
                .identifierQuote("`")
                .defaultDatabase("default")
                .build();
    }

    /**
     * SQLite 是嵌入式文件数据库，没有 host/port，也没有真正的 schema/catalog 概念
     * （见 {@code SqliteDatabaseStrategy} 顶部的偏离说明）。
     *
     * <p>{@code defaultPort}/{@code defaultSecurePort} 填 0——{@link GenericDatabaseStrategy}
     * 对"没有端口"已经是这个约定，沿用它而不是发明一个假端口。
     * sqlite-jdbc 实测 {@code supportsTransactions()}/{@code supportsSavepoints()} 均为
     * {@code true}（探测程序见开发记录），标准 UPDATE/DELETE 和 affected rows 均可靠，
     * 因此恢复 SQL 能力和 MySQL/PostgreSQL 同档，不必比照 ClickHouse 关闭。
     */
    public static DatabaseCapabilities SQLITE_DEFAULTS() {
        return DatabaseCapabilities.builder()
                .supportsTransactions(true)
                .supportsRecoverySql(true)
                .supportsDdl(true)
                .supportsShow(false)
                .affectedRowsReliable(true)
                .usesSchemaAsDatabase(false)
                .usesCatalogAsDatabase(false)
                .defaultPort(0)
                .defaultSecurePort(0)
                .identifierQuote("\"")
                .defaultDatabase("main")
                .build();
    }
}
