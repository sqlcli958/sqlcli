package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;
import com.sqlcli.graph.workspace.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClickHouseWorkspaceMetadataProvider implements WorkspaceMetadataProvider {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseWorkspaceMetadataProvider.class);

    /** CH-P2-008：定位 create_table_query 里 AS SELECT 之后的第一个 SELECT。 */
    private static final Pattern SELECT_KEYWORD = Pattern.compile("(?i)\\bSELECT\\b");
    /** 顶层第一个 FROM 后面的表名（可选 `db`.`table` 或 db.table 形式，反引号会被剥掉）。 */
    private static final Pattern FROM_TABLE = Pattern.compile("(?i)\\bFROM\\s+([`\\w]+(?:\\.[`\\w]+)?)");

    private final Connection connection;
    private final DatabaseConfig config;
    private final String sourceAlias;
    /** 按 schema 缓存 MaterializedView -> 源表，导入是逐表调 extractTable 的，
     * 但源表要从 create_table_query 里解析，整库一次查完更划算（同 rowEstimateCache 的思路）。 */
    private final Map<String, Map<String, String>> mvSourceCache = new HashMap<>();

    public ClickHouseWorkspaceMetadataProvider(Connection connection, DatabaseConfig config) {
        this.connection = connection;
        this.config = config;
        this.sourceAlias = config.getAliasName();
    }

    @Override
    public String getDatabaseType() {
        return "clickhouse";
    }

    @Override
    public boolean supportsForeignKeys() {
        return false;
    }

    @Override
    public boolean supportsIndexes() {
        return false;
    }

    @Override
    public String getProductName() throws SQLException {
        String value = connection.getMetaData().getDatabaseProductName();
        return value == null || value.isBlank() ? "unknown" : value;
    }

    @Override
    public String getProductVersion() throws SQLException {
        String value = connection.getMetaData().getDatabaseProductVersion();
        return value == null || value.isBlank() ? "unknown" : value;
    }

    @Override
    public List<String> discoverSchemas() throws SQLException {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT name FROM system.databases WHERE name NOT IN ('system', 'information_schema', 'INFORMATION_SCHEMA') ORDER BY name";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    schemas.add(name);
                }
            }
        } catch (SQLException e) {
            if (isPermissionDenied(e)) {
                log.warn("Permission denied for system.databases, falling back to JDBC DatabaseMetaData: {}", e.getMessage());
                return discoverSchemasFallback();
            }
            throw e;
        }
        return schemas;
    }

    /**
     * Fallback: discover schemas via JDBC DatabaseMetaData when system tables are not accessible.
     */
    private List<String> discoverSchemasFallback() throws SQLException {
        List<String> schemas = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getSchemas()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_SCHEM");
                if (name != null && !SystemSchemas.isSystem("clickhouse", name)) {
                    schemas.add(name);
                }
            }
        }
        return schemas;
    }

    @Override
    public List<String> discoverTables(String schemaName) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT name FROM system.tables WHERE database = ? ORDER BY name";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null) {
                        tables.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            if (isPermissionDenied(e)) {
                log.warn("Permission denied for system.tables, falling back to JDBC DatabaseMetaData: {}", e.getMessage());
                return discoverTablesFallback(schemaName);
            }
            throw e;
        }
        return tables;
    }

    /**
     * Fallback: discover tables via JDBC DatabaseMetaData when system tables are not accessible.
     */
    private List<String> discoverTablesFallback(String schemaName) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schemaName, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    @Override
    public TableExtractResult extractTable(String schemaName, String tableName) throws SQLException {
        TableWorkspaceNode table = TableWorkspaceNode.create(sourceAlias, schemaName, tableName, GraphActor.extractor);

        // Extract table-level info from system.tables
        extractTableInfo(schemaName, tableName, table);

        // Extract columns from system.columns
        extractColumns(schemaName, tableName, table);

        table.setStatus(SystemSchemas.isSystem("clickhouse", schemaName) ? GraphStatus.ignored : GraphStatus.partial);

        log.info("Extracted table {}.{}", schemaName, tableName);
        return new TableExtractResult(table);
    }

    private void extractTableInfo(String schemaName, String tableName, TableWorkspaceNode table) throws SQLException {
        String sql = "SELECT engine, comment, primary_key, sorting_key, partition_key, total_rows, metadata_modification_time " +
                "FROM system.tables WHERE database = ? AND name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String engine = rs.getString("engine");
                    String comment = rs.getString("comment");
                    String primaryKey = rs.getString("primary_key");
                    String sortingKey = rs.getString("sorting_key");
                    String partitionKey = rs.getString("partition_key");

                    table.setTableType(mapTableType(engine));

                    // Store ClickHouse-specific attributes
                    if (engine != null) {
                        table.getAttributes().put("engine", engine);
                    }
                    if (primaryKey != null && !primaryKey.isBlank()) {
                        table.getAttributes().put("primary_key", primaryKey);
                    }
                    if (sortingKey != null && !sortingKey.isBlank()) {
                        table.getAttributes().put("sorting_key", sortingKey);
                    }
                    if (partitionKey != null && !partitionKey.isBlank()) {
                        table.getAttributes().put("partition_key", partitionKey);
                    }

                    // Mark MaterializedView and, if resolvable, the source table it depends on
                    if (engine != null && "MaterializedView".equalsIgnoreCase(engine)) {
                        table.getAttributes().put("materialized", "true");
                        String source = materializedViewSources(schemaName).get(tableName);
                        if (source != null) {
                            table.getAttributes().put("depends_on_table", source);
                        }
                    }

                    // Set table comment
                    if (comment != null && !comment.isBlank()) {
                        table.setComment(comment);
                    }
                }
            }
        } catch (SQLException e) {
            if (isPermissionDenied(e)) {
                log.warn("Permission denied for system.tables (table info), falling back to JDBC DatabaseMetaData: {}", e.getMessage());
                extractTableInfoFallback(schemaName, tableName, table);
            } else {
                throw e;
            }
        }
    }

    /**
     * Fallback: extract basic table info via JDBC DatabaseMetaData when system.tables is not accessible.
     */
    private void extractTableInfoFallback(String schemaName, String tableName, TableWorkspaceNode table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schemaName, tableName, new String[]{"TABLE", "VIEW"})) {
            if (rs.next()) {
                String tableType = rs.getString("TABLE_TYPE");
                String remarks = rs.getString("REMARKS");

                if ("VIEW".equalsIgnoreCase(tableType)) {
                    table.setTableType(TableType.view);
                } else {
                    table.setTableType(TableType.base_table);
                }

                if (remarks != null && !remarks.isBlank()) {
                    table.setComment(remarks);
                }
            }
        }
    }

    private void extractColumns(String schemaName, String tableName, TableWorkspaceNode table) throws SQLException {
        String sql = "SELECT name, type, default_kind, default_expression, comment, is_in_primary_key " +
                "FROM system.columns WHERE database = ? AND table = ? ORDER BY position";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                int ordinal = 0;
                while (rs.next()) {
                    ordinal++;
                    String columnName = rs.getString("name");
                    String type = rs.getString("type");
                    String defaultKind = rs.getString("default_kind");
                    String defaultExpression = rs.getString("default_expression");
                    String columnComment = rs.getString("comment");

                    // is_in_primary_key may not exist in older ClickHouse versions
                    boolean isInPrimaryKey = false;
                    try {
                        isInPrimaryKey = rs.getBoolean("is_in_primary_key");
                    } catch (SQLException e) {
                        // Column does not exist, default to false
                    }

                    ColumnWorkspaceNode column = ColumnWorkspaceNode.create(columnName);
                    column.setOrdinal(ordinal);
                    column.setNullable(isNullable(type));
                    column.setPrimaryKey(isInPrimaryKey);

                    // Set default value for DEFAULT or MATERIALIZED kinds
                    if (defaultKind != null && ("DEFAULT".equalsIgnoreCase(defaultKind) || "MATERIALIZED".equalsIgnoreCase(defaultKind))) {
                        column.setDefaultValue(defaultExpression);
                    }

                    // Set comment
                    if (columnComment != null && !columnComment.isBlank()) {
                        column.setComment(columnComment);
                    }

                    // Set data type - keep full ClickHouse type as raw
                    column.getDataType().setRaw(type);
                    column.getDataType().setNormalized(normalizeType(type));

                    table.getColumns().add(column);
                    if (column.isPrimaryKey()) {
                        String columnId = column.computeId(sourceAlias, schemaName, tableName);
                        table.getPrimaryKey().add(columnId);
                    }
                }
            }
        } catch (SQLException e) {
            if (isPermissionDenied(e)) {
                log.warn("Permission denied for system.columns, falling back to JDBC DatabaseMetaData: {}", e.getMessage());
                extractColumnsFallback(schemaName, tableName, table);
            } else {
                throw e;
            }
        }
    }

    /**
     * Fallback: extract columns via JDBC DatabaseMetaData when system.columns is not accessible.
     */
    private void extractColumnsFallback(String schemaName, String tableName, TableWorkspaceNode table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(null, schemaName, tableName, null)) {
            int ordinal = 0;
            while (rs.next()) {
                ordinal++;
                String columnName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int nullable = rs.getInt("NULLABLE");
                String defaultValue = rs.getString("COLUMN_DEF");
                String remarks = rs.getString("REMARKS");

                ColumnWorkspaceNode column = ColumnWorkspaceNode.create(columnName);
                column.setOrdinal(ordinal);
                column.setNullable(nullable == DatabaseMetaData.columnNullable);
                column.getDataType().setRaw(typeName);
                column.getDataType().setNormalized(normalizeType(typeName));

                if (defaultValue != null && !defaultValue.isBlank()) {
                    column.setDefaultValue(defaultValue);
                }
                if (remarks != null && !remarks.isBlank()) {
                    column.setComment(remarks);
                }

                table.getColumns().add(column);
            }
        }

        // Extract primary key info from DatabaseMetaData
        try (ResultSet rs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                String pkColumn = rs.getString("COLUMN_NAME");
                for (ColumnWorkspaceNode col : table.getColumns()) {
                    if (col.getName().equals(pkColumn)) {
                        col.setPrimaryKey(true);
                        String columnId = col.computeId(sourceAlias, schemaName, tableName);
                        table.getPrimaryKey().add(columnId);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void extractForeignKeysForTable(TableWorkspaceNode table, GraphWorkspace target) throws SQLException {
        log.debug("ClickHouse does not support foreign keys, skipping");
    }

    @Override
    public void close() {
        // No-op: connection is managed externally
    }

    // --- CH-P2-008: MaterializedView 源表依赖 ---

    /**
     * 一个 schema 里全部 MaterializedView 的源表，按 schema 缓存。
     *
     * RelationType 只有 foreign_key / join_observed / term_mapping 三种，端点规则
     * （见 {@link com.sqlcli.graph.workspace.RelationEndpointRule}）都要求 column 级端点，
     * WorkspaceValidator 会把 table-to-table 的 join_observed 判成非法端点导致整次导入校验失败；
     * LineageRecord 建模的是"n 个源列推导 1 个目标列"，MV 依赖源表是表级结构依赖，硬套上去
     * 语义也不对。两种都不合适，因此不新增枚举值，复用 CH-P2-007 已经在用的 attributes：
     * 依赖表名写进 MV 自己的 {@code depends_on_table} 属性，和 engine/primary_key 同一套机制。
     */
    private Map<String, String> materializedViewSources(String schemaName) {
        return mvSourceCache.computeIfAbsent(schemaName, this::loadMaterializedViewSources);
    }

    /**
     * 整个 schema 一次查完，不逐表查 create_table_query——数百张表的库上逐表查会明显拖慢导入
     * （同 {@code WorkspaceMetadataExtractor#rowEstimate} 的缓存思路）。
     */
    private Map<String, String> loadMaterializedViewSources(String schemaName) {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT name, create_table_query FROM system.tables WHERE database = ? AND engine = 'MaterializedView'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String source = parseMaterializedViewSource(rs.getString("create_table_query"));
                    if (source != null) {
                        result.put(rs.getString("name"), source);
                    }
                }
            }
        } catch (SQLException e) {
            if (isPermissionDenied(e)) {
                log.warn("Permission denied for system.tables (create_table_query), skipping MV dependency detection: {}", e.getMessage());
            } else {
                log.debug("Failed to load MaterializedView sources for schema {}: {}", schemaName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 从 {@code create_table_query} 解析 MV 依赖的源表：{@code CREATE MATERIALIZED VIEW x
     * [TO y] AS SELECT ... FROM src ...} 里 AS SELECT 之后第一个顶层 FROM 的表名。
     *
     * ponytail: 只取第一个 FROM，不处理子查询、UNION、CTE、多表 JOIN 里的其余表——
     * depends_on 只需要回答"这张 MV 依赖哪张主要源表"，不是完整 SQL 血缘解析；
     * 真要做列级血缘再引入专门的 SQL parser。
     */
    static String parseMaterializedViewSource(String createTableQuery) {
        if (createTableQuery == null || createTableQuery.isBlank()) {
            return null;
        }
        Matcher selectMatcher = SELECT_KEYWORD.matcher(createTableQuery);
        if (!selectMatcher.find()) {
            return null;
        }
        Matcher fromMatcher = FROM_TABLE.matcher(createTableQuery.substring(selectMatcher.end()));
        if (!fromMatcher.find()) {
            return null;
        }
        return fromMatcher.group(1).replace("`", "");
    }

    // --- Helper methods ---

    private TableType mapTableType(String engine) {
        if (engine == null) {
            return TableType.unknown;
        }
        String lower = engine.toLowerCase(Locale.ROOT);
        // MergeTree family (most common ClickHouse table engines)
        if (lower.contains("mergetree")) {
            return TableType.base_table;
        }
        // Ordinary (legacy engine)
        if ("ordinary".equals(lower)) {
            return TableType.base_table;
        }
        // View
        if ("view".equals(lower)) {
            return TableType.view;
        }
        // MaterializedView - mapped to view with attribute marker
        if ("materializedview".equals(lower)) {
            return TableType.view;
        }
        // Dictionary
        if ("dictionary".equals(lower)) {
            return TableType.unknown;
        }
        // Other engines (Memory, File, Null, etc.)
        return TableType.unknown;
    }

    private boolean isNullable(String type) {
        return type != null && type.startsWith("Nullable");
    }

    private String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        // Strip Nullable wrapper for normalization
        String normalized = raw;
        if (normalized.startsWith("Nullable(") && normalized.endsWith(")")) {
            normalized = normalized.substring("Nullable(".length(), normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * Check if the SQLException indicates a permission/access denied error.
     * ClickHouse returns various error codes and messages for permission issues.
     */
    private boolean isPermissionDenied(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        // ClickHouse HTTP 403, SQL error codes, and common messages
        return lower.contains("not enough privileges")
                || lower.contains("access denied")
                || lower.contains("permission denied")
                || lower.contains("insufficient privileges")
                || lower.contains("403")
                || lower.contains("cannot select from system.")
                || lower.contains("mismatched input 'format'")
                || (e.getErrorCode() == 497)   // ClickHouse: NOT_ENOUGH_PRIVILEGES
                || (e.getErrorCode() == 636);   // ClickHouse: ACCESS_DENIED
    }
}
