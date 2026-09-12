package com.sqlcli.graph.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class WorkspaceMetadataExtractor implements WorkspaceMetadataProvider {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMetadataExtractor.class);

    private final Connection connection;
    private final DatabaseMetaData metaData;
    private final String databaseType;
    private final String sourceAlias;
    private final String currentCatalog;
    private final Map<String, Map<String, Long>> rowEstimateCache = new HashMap<>();

    public WorkspaceMetadataExtractor(Connection connection, String sourceAlias) throws SQLException {
        this.connection = connection;
        this.metaData = connection.getMetaData();
        this.databaseType = detectDatabaseType();
        this.sourceAlias = sourceAlias;
        this.currentCatalog = safeCurrentCatalog(connection);
    }

    public String getDatabaseType() {
        return databaseType;
    }

    @Override
    public String getProductName() throws SQLException {
        return unknownIfBlank(metaData.getDatabaseProductName());
    }

    @Override
    public String getProductVersion() throws SQLException {
        return unknownIfBlank(metaData.getDatabaseProductVersion());
    }

    /**
     * 全量提取：从数据库提取所有 schema、表、列和外键关系到一个新的 workspace。
     * 仅用于构建初始 workspace，不再删除已有数据。
     */
    public GraphWorkspace extract() throws SQLException {
        GraphWorkspace workspace = GraphWorkspace.create(sourceAlias, databaseType);
        List<String> schemas = discoverSchemas();
        for (String schemaName : schemas) {
            SchemaWorkspaceNode schemaNode = SchemaWorkspaceNode.create(sourceAlias, schemaName, GraphActor.extractor);
            if (isSystemSchema(schemaName)) {
                schemaNode.setSystem(true);
                schemaNode.setStatus(GraphStatus.ignored);
            }
            workspace.getSchemas().put(schemaNode.getId(), schemaNode);
            for (String tableName : discoverTables(schemaName)) {
                TableExtractResult result = extractTable(schemaName, tableName);
                workspace.getTables().put(result.table().getId(), result.table());
            }
        }
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            extractForeignKeysForTable(table, workspace);
        }
        return workspace;
    }

    /**
     * 单表行数估算，按 schema 缓存。
     *
     * 导入是逐表调 extractTable 的（WorkspaceImportService），所以这里必须按表取；
     * 但底层查询按 schema 整批做一次就够——499 张表查 499 次 information_schema
     * 会明显拖慢导入。同一个 provider 实例贯穿整个导入任务，缓存因此始终命中。
     */
    private Long rowEstimate(String schemaName, String tableName) {
        Map<String, Long> estimates =
                rowEstimateCache.computeIfAbsent(schemaName, this::readRowEstimates);
        return estimates.get(tableName.toLowerCase(Locale.ROOT));
    }

    /**
     * 整个 schema 的行数估算，一次查询取回。
     *
     * 用统计视图而不是 COUNT(*)：几百张表逐个精确计数会让导入慢到不可用，
     * 而表详情要的只是量级。统计值可能滞后（MySQL 的 TABLE_ROWS 对 InnoDB 是采样估算），
     * 拿不到就返回空 map，调用方保持原样不写入。
     */
    private Map<String, Long> readRowEstimates(String schemaName) {
        String sql;
        switch (databaseType) {
            case "mysql" -> sql = "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
            case "postgresql" -> sql = "SELECT c.relname AS TABLE_NAME, c.reltuples::bigint AS TABLE_ROWS "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm')";
            case "oracle" -> sql = "SELECT TABLE_NAME, NUM_ROWS AS TABLE_ROWS FROM ALL_TABLES WHERE OWNER = ?";
            default -> {
                return Map.of();
            }
        }
        Map<String, Long> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (ps == null) {
                return Map.of();
            }
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    long rows = rs.getLong(2);
                    if (name != null && !rs.wasNull()) {
                        result.put(name.toLowerCase(Locale.ROOT), rows);
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("Row estimates unavailable for schema {}: {}", schemaName, e.getMessage());
            return Map.of();
        }
        return result;
    }

    private String detectDatabaseType() throws SQLException {
        String url = metaData.getURL();
        if (url == null) {
            return "unknown";
        }
        if (url.contains("mysql")) {
            return "mysql";
        }
        if (url.contains("postgresql") || url.contains("postgres")) {
            return "postgresql";
        }
        if (url.contains("oracle")) {
            return "oracle";
        }
        if (url.contains("sqlserver")) {
            return "sqlserver";
        }
        if (url.contains("clickhouse")) {
            return "clickhouse";
        }
        if (url.contains("sqlite")) {
            return "sqlite";
        }
        return "unknown";
    }

    @Override
    public List<String> discoverSchemas() throws SQLException {
        // Note: For ClickHouse, prefer using ClickHouseWorkspaceMetadataProvider which queries system.databases directly
        // for richer metadata. The default JDBC getSchemas() behavior works since the V2 driver maps databases to schemas.
        List<String> schemas = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if ("sqlite".equals(databaseType)) {
            // sqlite-jdbc 实测 getSchemas()/getCatalogs() 都返回空结果集——SQLite 没有 JDBC
            // 意义上的 schema/catalog，枚举出来天然是空的。这正是之前直接拿
            // GenericDatabaseStrategy 跑 schema import 只导到 0 张表的原因。SQLite 自己确实
            // 有 schema 概念（PRAGMA database_list 能看到），主库固定叫 main，这里直接返回
            // 固定值，不做徒劳的探测。
            return List.of("main");
        }

        if ("mysql".equals(databaseType)) {
            // 注意：不能因为 URL 里带了 schema 就只返回它。
            // MySQL 的连接 schema 只是默认值，一个连接可以访问权限范围内的全部 schema；
            // 提前返回会让「按 schema 导入」拿不到目标 schema，也让图谱永远只有一个 schema。
            // 导入范围由 ImportOptions.schemaFilter 控制，不是由这里限制。
            if (currentCatalog != null && !currentCatalog.isBlank()) {
                schemas.add(currentCatalog);
                seen.add(currentCatalog.toLowerCase(Locale.ROOT));
            }
            try (ResultSet rs = metaData.getCatalogs()) {
                while (rs.next()) {
                    String catalog = rs.getString("TABLE_CAT");
                    if (catalog != null && seen.add(catalog.toLowerCase(Locale.ROOT))) {
                        schemas.add(catalog);
                    }
                }
            }
            return schemas;
        }

        try (ResultSet rs = metaData.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && seen.add(schema.toLowerCase(Locale.ROOT))) {
                    schemas.add(schema);
                }
            }
        }
        return schemas;
    }

    private String safeCurrentCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public List<String> discoverTables(String schemaName) throws SQLException {
        // Note: For ClickHouse, prefer using ClickHouseWorkspaceMetadataProvider which queries system.tables directly
        // for richer metadata (engine, partition key, etc.). The default JDBC getTables() behavior works as a fallback.
        List<String> tables = new ArrayList<>();
        String catalog = "mysql".equals(databaseType) ? schemaName : null;
        String schemaPattern = "mysql".equals(databaseType) ? null : schemaName;
        try (ResultSet rs = metaData.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName != null) {
                    tables.add(tableName);
                }
            }
        }
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        return tables;
    }

    @Override
    public TableExtractResult extractTable(String schemaName, String tableName) throws SQLException {
        String catalog = "mysql".equals(databaseType) ? schemaName : null;
        String schemaPattern = "mysql".equals(databaseType) ? null : schemaName;
        try (ResultSet rs = metaData.getTables(catalog, schemaPattern, tableName, new String[]{"TABLE", "VIEW"})) {
            if (!rs.next()) {
                // 表不存在，返回 null 表示跳过
                return null;
            }
            String tableType = rs.getString("TABLE_TYPE");
            String remarks = rs.getString("REMARKS");

            TableWorkspaceNode table = TableWorkspaceNode.create(sourceAlias, schemaName, tableName, GraphActor.extractor);
            table.setComment(remarks);
            table.setTableType(normalizeTableType(tableType));
            table.setRowEstimate(rowEstimate(schemaName, tableName));
            table.setStatus(isSystemSchema(schemaName) ? GraphStatus.ignored : GraphStatus.partial);

            extractColumns(schemaName, table);
            extractIndexes(schemaName, table);
            applyCommentGrain(table);

            log.info("Extracted table {}.{}", schemaName, tableName);
            return new TableExtractResult(table);
        }
    }

    private void extractColumns(String schemaName, TableWorkspaceNode table) throws SQLException {
        Set<String> primaryKeys = getPrimaryKeys(schemaName, table.getName());
        String catalog = "mysql".equals(databaseType) ? schemaName : null;
        String schemaPattern = "mysql".equals(databaseType) ? null : schemaName;
        try (ResultSet rs = metaData.getColumns(catalog, schemaPattern, table.getName(), "%")) {
            int ordinal = 0;
            while (rs.next()) {
                ordinal++;
                String columnName = rs.getString("COLUMN_NAME");
                ColumnWorkspaceNode column = ColumnWorkspaceNode.create(columnName);
                column.setOrdinal(ordinal);
                column.setComment(rs.getString("REMARKS"));
                column.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                column.setDefaultValue(rs.getString("COLUMN_DEF"));
                column.setPrimaryKey(primaryKeys.contains(columnName));
                column.getDataType().setRaw(rs.getString("TYPE_NAME"));
                column.getDataType().setNormalized(normalizeType(rs.getString("TYPE_NAME")));
                column.getDataType().setLength(rs.getInt("COLUMN_SIZE"));
                column.getDataType().setPrecision(rs.getInt("COLUMN_SIZE"));
                column.getDataType().setScale(rs.getInt("DECIMAL_DIGITS"));

                table.getColumns().add(column);
                if (column.isPrimaryKey()) {
                    String columnId = column.computeId(sourceAlias, schemaName, table.getName());
                    table.getPrimaryKey().add(columnId);
                }
            }
        }
        if ("mysql".equals(databaseType)) {
            enrichMySqlColumnExtras(schemaName, table);
        }
        if ("oracle".equals(databaseType)) {
            enrichOracleComments(schemaName, table);
        }
    }

    /**
     * 从表 / 列注释里拆出 grain（{@code COMMENT '订单行 | grain=一行一个订单商品'}），
     * 放在全部注释来源（JDBC REMARKS、MySQL/Oracle 的补充查询）都写完之后跑一遍，
     * 不用在每个 setComment 调用点都插一遍解析。列注释没有 grain 落点，丢弃即可。
     */
    private void applyCommentGrain(TableWorkspaceNode table) {
        CommentGrainParser.Parsed parsed = CommentGrainParser.parse(table.getComment());
        table.setComment(parsed.comment());
        if (parsed.grain() != null) {
            table.setGrain(parsed.grain());
        }
        for (ColumnWorkspaceNode column : table.getColumns()) {
            column.setComment(CommentGrainParser.parse(column.getComment()).comment());
        }
    }

    private void enrichMySqlColumnExtras(String schemaName, TableWorkspaceNode table) throws SQLException {
        String sql = """
                SELECT COLUMN_NAME, EXTRA
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setString(2, table.getName());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ColumnWorkspaceNode column = table.findColumn(rs.getString("COLUMN_NAME"));
                    String onUpdate = onUpdateExpression(rs.getString("EXTRA"));
                    if (column != null && onUpdate != null) {
                        column.getAttributes().put("onUpdate", onUpdate);
                    }
                }
            }
        }
    }

    private String onUpdateExpression(String extra) {
        if (extra == null) return null;
        String lower = extra.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("on update ");
        if (marker < 0) return null;
        String remainder = extra.substring(marker + "on update ".length()).trim();
        int whitespace = remainder.indexOf(' ');
        return whitespace < 0 ? remainder : remainder.substring(0, whitespace);
    }

    private void extractIndexes(String schemaName, TableWorkspaceNode table) throws SQLException {
        String catalog = "mysql".equals(databaseType) ? schemaName : null;
        String schemaPattern = "mysql".equals(databaseType) ? null : schemaName;
        Map<String, IndexRows> indexes = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getIndexInfo(catalog, schemaPattern, table.getName(), false, false)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || name.isBlank() || column == null || column.isBlank()) {
                    continue;
                }
                IndexRows rows = indexes.computeIfAbsent(name,
                        ignored -> new IndexRows(name, !readBoolean(rs, "NON_UNIQUE")));
                rows.columns.put(rs.getInt("ORDINAL_POSITION"), column);
            }
        }
        for (IndexRows rows : indexes.values()) {
            TableIndexMetadata index = new TableIndexMetadata();
            index.setName(rows.name);
            index.setUnique(rows.unique);
            index.setColumns(new ArrayList<>(rows.columns.values()));
            table.getIndexes().add(index);
            for (String columnName : index.getColumns()) {
                ColumnWorkspaceNode column = table.findColumn(columnName);
                if (column != null) {
                    column.setIndexed(true);
                    if (index.isUnique() && index.getColumns().size() == 1) {
                        column.setUnique(true);
                    }
                }
            }
        }
    }

    private boolean readBoolean(ResultSet rs, String column) {
        try {
            return rs.getBoolean(column);
        } catch (SQLException e) {
            return false;
        }
    }

    private record IndexRows(String name, boolean unique, TreeMap<Integer, String> columns) {
        private IndexRows(String name, boolean unique) {
            this(name, unique, new TreeMap<>());
        }
    }

    private void enrichOracleComments(String schemaName, TableWorkspaceNode table) throws SQLException {
        boolean tableCommentMissing = table.getComment() == null || table.getComment().isBlank();
        boolean hasColumnWithoutComment = table.getColumns().stream()
                .anyMatch(col -> col.getComment() == null || col.getComment().isBlank());
        if (!tableCommentMissing && !hasColumnWithoutComment) {
            return;
        }
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schemaName);
            ps.setString(2, table.getName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && tableCommentMissing) {
                    String comments = rs.getString("COMMENTS");
                    if (comments != null && !comments.isBlank()) {
                        table.setComment(comments);
                    }
                }
            }
        }
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schemaName);
            ps.setString(2, table.getName());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    String comments = rs.getString("COMMENTS");
                    if (colName != null && comments != null && !comments.isBlank()) {
                        ColumnWorkspaceNode col = table.findColumn(colName);
                        if (col != null && (col.getComment() == null || col.getComment().isBlank())) {
                            col.setComment(comments);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void extractForeignKeysForTable(TableWorkspaceNode table, GraphWorkspace target) throws SQLException {
        String alias = table.getSourceAlias();
        String catalog = "mysql".equals(databaseType) ? table.getSchema() : null;
        String schemaPattern = "mysql".equals(databaseType) ? null : table.getSchema();
        // JDBC 对复合外键（多列约束）逐列返回一行，FK_NAME 相同、KEY_SEQ 从 1 递增。
        // 必须先把整表的行收集齐再分组——边读边建边会把同一约束的多列拆成互不相关的
        // foreign_key 边，Agent 沿其中一条生成 JOIN 时会漏掉另一列（复合主键里常见的
        // tenant_id 首当其冲），产出跨租户笛卡尔积却不报错。
        List<ImportedKeyRow> rows = new ArrayList<>();
        try (ResultSet rs = metaData.getImportedKeys(catalog, schemaPattern, table.getName())) {
            while (rs.next()) {
                rows.add(new ImportedKeyRow(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_SCHEM"),
                        rs.getString("PKTABLE_CAT"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME"),
                        rs.getString("FK_NAME"),
                        rs.getInt("KEY_SEQ")));
            }
        }
        Set<String> seen = new HashSet<>();
        for (List<ImportedKeyRow> group : groupByConstraint(rows)) {
            addForeignKeyGroup(alias, table, target, group, seen);
        }
    }

    /**
     * 按约束把行分组，优先用 FK_NAME，拿不到才退回 KEY_SEQ 重置的启发式。
     *
     * 一开始按 KEY_SEQ 重置分组、不看 FK_NAME 是错的：JDBC 对 getImportedKeys 的排序约定是
     * PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, KEY_SEQ——排序键里没有 FK_NAME。
     * 于是同一子表对**同一张父表**有两个复合外键时（比如单据表的 from_warehouse /
     * to_warehouse 都指向 warehouse(tenant_id, code)，这在挂了租户列的国内库里不罕见），
     * 两个约束的行会按 KEY_SEQ 交错返回：(1,fk_from) (1,fk_to) (2,fk_from) (2,fk_to)。
     * 只看「KEY_SEQ 重置为 1」分组会把这四行错分成两组，一组缺列、另一组混进另一个约束的列，
     * 拼出来的 joinExpression 比原来「拆成 N 条独立边」的 bug 更糟——原来只是漏列，
     * 这样会把两个约束的列拼进同一个 AND。
     *
     * FK_NAME 在同一子表内唯一（MySQL / PostgreSQL / Oracle 都返回它），按名字分组不受
     * 交错顺序影响，天然正确，因此优先用它。只有驱动确实不给 FK_NAME 时才退回 KEY_SEQ 重置
     * 这套启发式——这时没有更好的信号，交错场景本身也无法可靠区分，接受这个已知上限。
     */
    private List<List<ImportedKeyRow>> groupByConstraint(List<ImportedKeyRow> rows) {
        Map<String, List<ImportedKeyRow>> byName = new LinkedHashMap<>();
        List<List<ImportedKeyRow>> unnamed = new ArrayList<>();
        for (ImportedKeyRow row : rows) {
            if (row.fkName() != null && !row.fkName().isBlank()) {
                byName.computeIfAbsent(row.fkName(), k -> new ArrayList<>()).add(row);
            } else {
                // 退化路径：驱动不给 FK_NAME 时才靠 KEY_SEQ 重置分组（<=1 开新组，
                // 覆盖驱动连 KEY_SEQ 也不给、getInt 恒为 0 的情况——每行独立成组，
                // 等价于最早的逐行行为，不会因为拿不到这一列就出错）。
                if (unnamed.isEmpty() || row.keySeq() <= 1) {
                    unnamed.add(new ArrayList<>());
                }
                unnamed.get(unnamed.size() - 1).add(row);
            }
        }
        List<List<ImportedKeyRow>> groups = new ArrayList<>(byName.values());
        groups.addAll(unnamed);
        return groups;
    }

    /**
     * 单个约束的分组标识。优先用 FK_NAME——同一子表内约束名唯一，天然可做分组键；
     * 部分驱动不返回 FK_NAME 时，不能因此退回「一条边一组」的旧行为（那样复合外键
     * 又被拆散了），改用「止表 + 全部列对排序后」推导一个稳定标识。
     */
    private String stableGroupKey(TableWorkspaceNode table, List<ImportedKeyRow> group) {
        ImportedKeyRow first = group.get(0);
        if (first.fkName() != null && !first.fkName().isBlank()) {
            return first.fkName();
        }
        String targetTable = resolveSchema(first.pkSchema(), first.pkCatalog(), table.getSchema())
                + "." + first.pkTableName();
        String columnPairs = group.stream()
                .map(r -> r.fkColumnName() + "->" + r.pkColumnName())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return targetTable + "#" + columnPairs;
    }

    private void addForeignKeyGroup(String alias, TableWorkspaceNode table, GraphWorkspace target,
            List<ImportedKeyRow> group, Set<String> seen) {
        List<ImportedKeyRow> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingInt(ImportedKeyRow::keySeq));
        String groupKey = stableGroupKey(table, sorted);
        // 组内所有边共享同一条完整 joinExpression：BFS 找路径时任取组里哪一条边，
        // 拿到的都是全部列对拼成的 AND 条件，不会因为选中了组里的某一条而漏列。
        // 单列约束这里天然退化成和改动前逐字相同的单个等式，没有额外分支。
        String combinedJoinExpression = sorted.stream()
                .map(row -> table.getSchema() + "." + table.getName() + "." + row.fkColumnName()
                        + " = " + resolveSchema(row.pkSchema(), row.pkCatalog(), table.getSchema())
                        + "." + row.pkTableName() + "." + row.pkColumnName())
                .collect(java.util.stream.Collectors.joining(" AND "));
        for (ImportedKeyRow row : sorted) {
            addForeignKeyRelation(alias, table, target, row, groupKey, combinedJoinExpression, seen);
        }
    }

    private void addForeignKeyRelation(String alias, TableWorkspaceNode table, GraphWorkspace target,
            ImportedKeyRow row, String groupKey, String joinExpression, Set<String> seen) {
        String targetSchema = resolveSchema(row.pkSchema(), row.pkCatalog(), table.getSchema());
        String fromId = GraphIds.columnId(alias, table.getSchema(), table.getName(), row.fkColumnName());
        String toId = GraphIds.columnId(alias, targetSchema, row.pkTableName(), row.pkColumnName());
        ColumnWorkspaceNode fromCol = table.findColumn(row.fkColumnName());
        // 查找目标表：先从 target workspace 找，找不到则从列的所属表 ID 推断
        TableWorkspaceNode targetTable = target.getTables().get(GraphIds.tableId(alias, targetSchema, row.pkTableName()));
        ColumnWorkspaceNode toCol = targetTable != null ? targetTable.findColumn(row.pkColumnName()) : null;
        if (fromCol == null || toCol == null) {
            return;
        }
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(
                alias, RelationType.foreign_key, fromId, toId, GraphActor.extractor);
        edge.setCardinality(RelationCardinality.many_to_one);
        edge.setConfidence(1.0);
        edge.setVerified(true);
        edge.setStatus(GraphStatus.verified);
        edge.setJoinExpression(joinExpression);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_GROUP, groupKey);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_KEY_SEQ, row.keySeq());
        // 参与约束 optionality：from 端（外键列所在行）nullable=true 时可选——外键列
        // 允许为空，意味着这一行可以不参照任何父行，INNER JOIN 会把它静默过滤掉。
        // to 端（父表主键行）是否必然有子行，单列 nullable 推不出来，保守按可选处理，
        // 宁可多出一次不必要的 LEFT JOIN，也不要因为默认成 INNER 而静默丢行。
        // 这是推断初值，不是确认值，标记 optionalitySource=inferred，等人在 UI/CLI 确认。
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, fromCol.isNullable());
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE,
                RelationWorkspaceEdge.OPTIONALITY_SOURCE_INFERRED);
        edge.getEvidence().add(RelationEvidence.of("jdbc_metadata", "foreign-key:" + table.getQualifiedName()));
        if (!seen.add(edge.getId())) {
            return;
        }
        // 仅在 target workspace 中去重和添加，不删除已有关系
        target.getRelations().removeIf(existing -> existing.getId().equals(edge.getId()));
        target.getRelations().add(edge);
    }

    /** getImportedKeys 单行的快照。先整表收集齐再分组，分组要看完整的列集合才能定组标识和拼 joinExpression。 */
    private record ImportedKeyRow(String fkColumnName, String pkSchema, String pkCatalog, String pkTableName,
            String pkColumnName, String fkName, int keySeq) {
    }

    private String resolveSchema(String schema, String catalog, String fallback) {
        String result = schema != null ? schema : catalog;
        return result != null ? result : fallback;
    }

    private Set<String> getPrimaryKeys(String schemaName, String tableName) throws SQLException {
        Set<String> keys = new HashSet<>();
        String catalog = "mysql".equals(databaseType) ? schemaName : null;
        String schemaPattern = "mysql".equals(databaseType) ? null : schemaName;
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schemaPattern, tableName)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    // isSystemSchema：不再覆写，用 WorkspaceMetadataProvider 默认方法（委托 SystemSchemas，DB-P3-006）。

    private TableType normalizeTableType(String raw) {
        if (raw == null) {
            return TableType.unknown;
        }
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "TABLE" -> TableType.base_table;
            case "VIEW" -> TableType.view;
            case "SYSTEM TABLE" -> TableType.system_table;
            default -> TableType.unknown;
        };
    }

    private String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private String unknownIfBlank(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
