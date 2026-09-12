package com.sqlcli.graph.workspace;

import com.sqlcli.strategy.SqliteDatabaseStrategy;
import com.sqlcli.strategy.TableInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 元数据抽取的端到端验证，用真实的临时 sqlite 文件（不是 mock），因为
 * SQLite 是嵌入式数据库，不需要 Testcontainers 就能测真实驱动行为。
 *
 * <p>覆盖 SqliteDatabaseStrategy 类注释里承诺的三件事，用真实数据反过来验证承诺没有落空：
 * schema 固定是 "main"（{@link WorkspaceMetadataExtractor#discoverSchemas}）、
 * 表/字段抽取靠标准 JDBC 元数据（{@link WorkspaceMetadataExtractor#extractTable}）、
 * 复合外键在 FK_NAME 为空字符串时靠 KEY_SEQ 回退分组正确聚合
 * （{@link WorkspaceMetadataExtractor#extractForeignKeysForTable}）。
 */
class SqliteWorkspaceMetadataIntegrationTest {

    private Connection open(Path dbFile) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, code TEXT, name TEXT)");
            st.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, "
                    + "parent_id INTEGER, parent_code TEXT, label TEXT, "
                    + "FOREIGN KEY (parent_id, parent_code) REFERENCES parent(id, code))");
            st.execute("CREATE VIEW parent_view AS SELECT id, name FROM parent");
            st.execute("INSERT INTO parent VALUES (1, 'A1', 'first')");
        }
    }

    @Test
    void discoverSchemasReturnsFixedMain(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("meta.db");
        try (Connection conn = open(dbFile)) {
            createSchema(conn);
            WorkspaceMetadataExtractor provider = new WorkspaceMetadataExtractor(conn, "test-alias");

            assertEquals("sqlite", provider.getDatabaseType());
            assertEquals(List.of("main"), provider.discoverSchemas());
            assertFalse(provider.isSystemSchema("main"), "main 不是系统 schema");
        }
    }

    @Test
    void discoverTablesExcludesSqliteSystemTablesAndFindsViews(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("meta.db");
        try (Connection conn = open(dbFile)) {
            createSchema(conn);
            WorkspaceMetadataExtractor provider = new WorkspaceMetadataExtractor(conn, "test-alias");

            List<String> tables = provider.discoverTables("main");
            assertEquals(List.of("child", "parent", "parent_view"), tables,
                    "sqlite_* 系统表必须被驱动自身的 TABLE/VIEW 类型过滤排除掉，不需要额外过滤代码");
        }
    }

    @Test
    void extractTableGetsColumnsAndSkipsNullComments(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("meta.db");
        try (Connection conn = open(dbFile)) {
            createSchema(conn);
            WorkspaceMetadataExtractor provider = new WorkspaceMetadataExtractor(conn, "test-alias");

            TableExtractResult result = provider.extractTable("main", "parent");
            TableWorkspaceNode table = result.table();
            assertEquals(3, table.getColumns().size());
            assertNull(table.getComment(), "SQLite 没有 COMMENT 语法，注释为 null 是正常情况，不是抓取失败");
            assertTrue(table.getColumns().stream().allMatch(c -> c.getComment() == null));
            ColumnWorkspaceNode idColumn = table.findColumn("id");
            assertNotNull(idColumn);
            assertTrue(idColumn.isPrimaryKey());
        }
    }

    /**
     * 复合外键分组的实测：child 表对 parent 表有一个两列的复合外键
     * (parent_id, parent_code) -> (id, code)。sqlite-jdbc 对 getImportedKeys 返回的
     * FK_NAME 是空字符串（不是 null），落进 WorkspaceMetadataExtractor#groupByConstraint
     * 的 KEY_SEQ 重置退化分组路径。这里验证两列被分进同一条 foreign_key 关系，
     * 而不是拆成两条独立的边（拆开会让 JOIN 推导漏掉一列，产出笛卡尔积）。
     */
    @Test
    void compositeForeignKeyIsGroupedIntoOneRelation(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("meta.db");
        try (Connection conn = open(dbFile)) {
            createSchema(conn);
            WorkspaceMetadataExtractor provider = new WorkspaceMetadataExtractor(conn, "test-alias");

            GraphWorkspace workspace = GraphWorkspace.create("test-alias", "sqlite");
            for (String tableName : List.of("parent", "child")) {
                TableExtractResult result = provider.extractTable("main", tableName);
                workspace.getTables().put(result.table().getId(), result.table());
            }
            TableWorkspaceNode childTable = workspace.getTables().values().stream()
                    .filter(t -> "child".equals(t.getName())).findFirst().orElseThrow();
            provider.extractForeignKeysForTable(childTable, workspace);

            List<RelationWorkspaceEdge> fkEdges = workspace.getRelations().stream()
                    .filter(e -> e.getType() == RelationType.foreign_key)
                    .toList();
            assertEquals(2, fkEdges.size(),
                    "两列复合外键应产出两条边（每列一条），但共享同一个 fkGroup 和完整 joinExpression");

            Map<String, List<RelationWorkspaceEdge>> byGroup = fkEdges.stream()
                    .collect(Collectors.groupingBy(
                            e -> String.valueOf(e.getAttributes().get(RelationWorkspaceEdge.ATTR_FK_GROUP))));
            assertEquals(1, byGroup.size(),
                    "两列必须落进同一个 fkGroup，证明 KEY_SEQ 回退分组把复合外键当成一个约束处理，而不是拆成两个独立约束");

            String joinExpression = fkEdges.get(0).getJoinExpression();
            assertTrue(joinExpression.contains("parent_id") && joinExpression.contains("parent_code"),
                    "joinExpression 必须包含复合外键的全部列对，缺一列会让 Agent 生成的 JOIN 产出笛卡尔积: "
                            + joinExpression);
            assertTrue(joinExpression.contains(" AND "), "两列条件应以 AND 拼接: " + joinExpression);
        }
    }

    @Test
    void sqliteDatabaseStrategyGetTableDdlAndListTables(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("meta.db");
        try (Connection conn = open(dbFile)) {
            createSchema(conn);
            SqliteDatabaseStrategy strategy = new SqliteDatabaseStrategy();

            String ddl = strategy.getTableDdl(conn, "parent", "main");
            assertTrue(ddl.toUpperCase().contains("CREATE TABLE"));
            assertTrue(ddl.contains("parent"));

            List<TableInfo> tables = strategy.listTables(conn, "main", null);
            assertEquals(3, tables.size(), "schema import 必须真的导出表，不能是 0 张");
            assertTrue(tables.stream().allMatch(t -> "main".equals(t.getSchema())));
            assertTrue(tables.stream().anyMatch(t -> "parent_view".equals(t.getName()) && "VIEW".equals(t.getType())));
        }
    }
}
