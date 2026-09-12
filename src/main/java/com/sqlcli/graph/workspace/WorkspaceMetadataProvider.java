package com.sqlcli.graph.workspace;

import java.sql.SQLException;
import java.util.List;

public interface WorkspaceMetadataProvider extends AutoCloseable {
    String getDatabaseType();

    default String getProductName() throws SQLException {
        return "unknown";
    }

    default String getProductVersion() throws SQLException {
        return "unknown";
    }

    default boolean supportsIndexes() {
        return true;
    }

    default boolean supportsForeignKeys() {
        return true;
    }

    List<String> discoverSchemas() throws SQLException;

    /**
     * 系统 schema：可以列出但默认不导入，导入了也只是噪声。
     *
     * 放在接口上是因为「列出可导入的 schema」和「导入时跳过系统 schema」是同一套规则，
     * 分两处写迟早会不一致。判断逻辑委托给 {@link SystemSchemas}——DB-P3-006：
     * 这份清单以前在这里、{@code WorkspaceMetadataExtractor} 和 ClickHouse provider
     * 各存一份，三处已经不一致（Oracle 的清单漂移了），现在唯一实现只有一处。
     */
    default boolean isSystemSchema(String schemaName) {
        return SystemSchemas.isSystem(getDatabaseType(), schemaName);
    }


    List<String> discoverTables(String schemaName) throws SQLException;

    /**
     * 从数据库提取单个表的元数据。
     * 不修改已有 workspace，产出结果用于后续合并。
     */
    TableExtractResult extractTable(String schemaName, String tableName) throws SQLException;

    /**
     * 从数据库提取指定表的外键关系，添加到目标 workspace 中。
     * 仅添加 foreign_key 类型的关系，不删除已有关系。
     */
    void extractForeignKeysForTable(TableWorkspaceNode table, GraphWorkspace target) throws SQLException;

    @Override
    default void close() throws Exception {
    }
}
