package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.WorkspaceMetadataProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 数据库方言策略接口
 */
public interface DatabaseStrategy {
    String type();

    String buildJdbcUrl(DatabaseConfig config);

    void applyConnectionProperties(DatabaseConfig config, Properties properties);

    String preprocessSql(DatabaseConfig config, String sql);

    List<String> buildConnectionHints(DatabaseConfig config, SQLException exception, Throwable rootCause);

    /**
     * 获取表的DDL语句
     * @param conn 数据库连接
     * @param tableName 表名
     * @param schemaName 模式名（可选，Oracle需要）
     * @return DDL语句
     */
    String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException;

    /**
     * 获取数据库中的表列表
     * @param conn 数据库连接
     * @param schemaName 模式名（可选）
     * @param pattern 表名匹配模式（可选，如 %USER%）
     * @return 表信息列表
     */
    List<TableInfo> listTables(Connection conn, String schemaName, String pattern) throws SQLException;

    /**
     * Collect runtime database/session information used by agents to generate correct SQL.
     */
    Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException;

    /**
     * 返回此数据库类型的能力描述
     */
    DatabaseCapabilities capabilities();

    /**
     * 返回此数据库类型的SQL执行策略
     */
    SqlExecutionPolicy executionPolicy();

    /**
     * Resolve the schema/database used by metadata commands when --schema is omitted.
     */
    default String defaultSchema(DatabaseConfig config) {
        return null;
    }

    /**
     * 使用数据库的引号字符引用标识符
     * @param identifier 要引用的标识符
     * @return 引用后的标识符
     */
    String quoteIdentifier(String identifier);

    /**
     * 使用模式名限定表名
     * @param schemaName 模式名（可为null或空）
     * @param tableName 表名
     * @return 限定后的表名
     */
    String qualifyTableName(String schemaName, String tableName);

    /**
     * 创建此数据库类型的元数据提供者
     * @param conn 数据库连接
     * @param config 数据库配置
     * @return 元数据提供者
     */
    WorkspaceMetadataProvider createMetadataProvider(Connection conn, DatabaseConfig config) throws SQLException;
}
