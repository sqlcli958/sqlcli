package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceImportServiceTest {

    @TempDir
    Path root;

    @Test
    void batchSizeOneMustNotDeprecateTablesFromOtherBatches() throws Exception {
        TestHarness harness = new TestHarness(root);
        FakeProvider provider = new FakeProvider()
                .table("orders", "id")
                .table("users", "id");

        harness.importFrom(provider, 1, null);
        harness.importFrom(provider, 1, null);

        GraphWorkspace workspace = harness.load();
        assertEquals(GraphStatus.partial, workspace.getTableByQualifiedName("trade.orders").getStatus());
        assertEquals(GraphStatus.partial, workspace.getTableByQualifiedName("trade.users").getStatus());
        ChangeRecord change = workspace.getChanges().stream()
                .filter(item -> "database import batch".equals(item.getReason())).findFirst().orElseThrow();
        assertEquals(GraphActor.extractor, change.getActor());
        assertNotNull(change.getBeforeHash());
        assertNotNull(change.getAfterHash());
    }

    @Test
    void foreignKeyMustResolveAcrossBatchesAndBeRemovedWhenDatabaseReturnsNone() throws Exception {
        TestHarness harness = new TestHarness(root);
        FakeProvider withFk = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id");

        harness.importFrom(withFk, 1, null);
        assertEquals(1, harness.load().getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).count());

        FakeProvider withoutFk = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id");
        harness.importFrom(withoutFk, 1, null);

        assertEquals(0, harness.load().getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).count());
    }

    @Test
    void foreignKeyMustResolveWhenReferencedSchemaSortsLater() throws Exception {
        TestHarness harness = new TestHarness(root);
        FakeProvider provider = new FakeProvider()
                .tableInSchema("a_sales", "orders", "id", "customer_id")
                .tableInSchema("z_identity", "customers", "id")
                .foreignKey("a_sales", "orders", "customer_id", "z_identity", "customers", "id");

        harness.importFrom(provider, 1, null);

        assertTrue(harness.load().getRelations().stream().anyMatch(relation ->
                relation.getFrom().endsWith("a_sales.orders.customer_id")
                        && relation.getTo().endsWith("z_identity.customers.id")));
    }

    @Test
    void completedRefreshMustDeprecateAndLaterRestoreTablesAndColumns() throws Exception {
        TestHarness harness = new TestHarness(root);
        harness.importFrom(new FakeProvider()
                .table("orders", "id", "old_col")
                .table("users", "id"), 1, null);

        harness.importFrom(new FakeProvider().table("orders", "id"), 1, null);
        GraphWorkspace deprecated = harness.load();
        assertEquals(GraphStatus.deprecated, deprecated.getTableByQualifiedName("trade.users").getStatus());
        assertEquals(true, deprecated.getTableByQualifiedName("trade.orders")
                .findColumn("old_col").getAttributes().get("deprecated"));

        harness.importFrom(new FakeProvider()
                .table("orders", "id", "old_col")
                .table("users", "id"), 1, null);
        GraphWorkspace restored = harness.load();
        assertEquals(GraphStatus.partial, restored.getTableByQualifiedName("trade.users").getStatus());
        assertFalse(restored.getTableByQualifiedName("trade.orders")
                .findColumn("old_col").getAttributes().containsKey("deprecated"));
        assertTrue(restored.getChanges().stream().anyMatch(c ->
                "table restored by database import".equals(c.getReason())));
        assertTrue(restored.getChanges().stream().anyMatch(c ->
                "column restored by database import".equals(c.getReason())));
    }

    @Test
    void failedRefreshMustNotDeprecUnfinishedTables() throws Exception {
        TestHarness harness = new TestHarness(root);
        harness.importFrom(new FakeProvider()
                .table("orders", "id")
                .table("users", "id"), 1, null);

        FakeProvider failing = new FakeProvider()
                .table("orders", "id")
                .table("users", "id")
                .fail("users");
        ImportJob job = harness.importFrom(failing, 1, null);

        assertEquals(ImportJobStatus.failed, job.getStatus());
        assertNotEquals(GraphStatus.deprecated,
                harness.load().getTableByQualifiedName("trade.users").getStatus());
    }

    @Test
    void tableFilterMustNotAffectSiblings() throws Exception {
        TestHarness harness = new TestHarness(root);
        harness.importFrom(new FakeProvider()
                .table("orders", "id")
                .table("users", "id"), 1, null);

        ImportOptions options = new ImportOptions();
        options.setBatchSize(1);
        options.setTableFilter("trade.users");
        harness.service.start("unit", new FakeProvider().table("orders", "id"), options);

        GraphWorkspace workspace = harness.load();
        assertEquals(GraphStatus.deprecated, workspace.getTableByQualifiedName("trade.users").getStatus());
        assertNotEquals(GraphStatus.deprecated, workspace.getTableByQualifiedName("trade.orders").getStatus());
    }

    @Test
    void finalGraphMustNotDependOnBatchSize() throws Exception {
        GraphWorkspace one = importSnapshot(root.resolve("one"), 1);
        GraphWorkspace twenty = importSnapshot(root.resolve("twenty"), 20);

        assertEquals(one.getTables().keySet(), twenty.getTables().keySet());
        assertEquals(one.getRelations().stream().map(RelationWorkspaceEdge::getId).toList(),
                twenty.getRelations().stream().map(RelationWorkspaceEdge::getId).toList());
    }

    @Test
    void foreignKeyImportMustReuseOneResolutionContext() throws Exception {
        TestHarness harness = new TestHarness(root);
        FakeProvider provider = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("invoices", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id")
                .foreignKey("invoices", "user_id", "users", "id");

        harness.importFrom(provider, 1, null);

        assertEquals(1, provider.foreignKeyTargetCount());
        assertEquals(2, harness.load().getRelations().stream()
                .filter(relation -> relation.getType() == RelationType.foreign_key).count());
    }

    @Test
    void dataSourceMetadataMustSurviveBatchMerge() throws Exception {
        TestHarness harness = new TestHarness(root);

        harness.importFrom(new FakeProvider().table("orders", "id"), 1, null);

        DataSourceNode dataSource = harness.load().getDataSource();
        assertEquals("MySQL", dataSource.getProductName());
        assertEquals("8.4.0", dataSource.getProductVersion());
        assertTrue(dataSource.getIndexMetadataSupported());
    }

    @Test
    void completedEmptySchemaRefreshMustDeprecateItsLastTable() throws Exception {
        TestHarness harness = new TestHarness(root);
        harness.importFrom(new FakeProvider().table("orders", "id"), 1, null);

        harness.importFrom(new FakeProvider().schema("trade"), 1, null);

        assertEquals(GraphStatus.deprecated,
                harness.load().getTableByQualifiedName("trade.orders").getStatus());
    }

    // ---------------------------------------------------------------- 回归矩阵补全（生命周期清单 3.4）

    @Test
    void refreshWithoutChangesMustKeepWorkspaceStable() throws Exception {
        TestHarness harness = new TestHarness(root);
        FakeProvider provider = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id");
        harness.importFrom(provider, 1, null);
        GraphWorkspace first = harness.load();

        harness.importFrom(provider, 20, null);
        GraphWorkspace second = harness.load();

        assertEquals(first.getTables().keySet(), second.getTables().keySet());
        assertEquals(first.getRelations().stream().map(RelationWorkspaceEdge::getId).toList(),
                second.getRelations().stream().map(RelationWorkspaceEdge::getId).toList());
        assertTrue(second.getTables().values().stream().noneMatch(
                t -> t.getStatus() == GraphStatus.deprecated), "无变化刷新不该产生 deprecated");
    }

    @Test
    void bidirectionalForeignKeysMustNotDuplicateAcrossRefreshes() throws Exception {
        TestHarness harness = new TestHarness(root);
        FakeProvider provider = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id", "last_order_id")
                .foreignKey("orders", "user_id", "users", "id")
                .foreignKey("users", "last_order_id", "orders", "id");

        harness.importFrom(provider, 1, null);
        harness.importFrom(provider, 20, null);

        List<String> fkIds = harness.load().getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key)
                .map(RelationWorkspaceEdge::getId).sorted().toList();
        assertEquals(2, fkIds.size(), "双向 FK 应恰好两条：不重复、不丢失");
        assertEquals(fkIds.stream().distinct().count(), fkIds.size());
    }

    @Test
    void resumedImportMustMatchSingleShotResult() throws Exception {
        // 一次性成功导入作为基准
        TestHarness clean = new TestHarness(root.resolve("clean"));
        FakeProvider full = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id");
        clean.importFrom(full, 1, null);
        GraphWorkspace expected = clean.load();

        // 失败一次再 resume
        TestHarness resumed = new TestHarness(root.resolve("resumed"));
        FakeProvider failing = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id")
                .fail("users");
        try {
            resumed.importFrom(failing, 1, null);
        } catch (Exception ignored) {
            // 预期失败
        }
        resumed.service.resume("unit", full);
        GraphWorkspace actual = resumed.load();

        assertEquals(expected.getTables().keySet(), actual.getTables().keySet());
        assertEquals(expected.getRelations().stream().map(RelationWorkspaceEdge::getId).sorted().toList(),
                actual.getRelations().stream().map(RelationWorkspaceEdge::getId).sorted().toList());
        assertTrue(actual.getTables().values().stream().noneMatch(
                t -> t.getStatus() == GraphStatus.deprecated), "resume 不得误废弃");
    }

    @Test
    void providerWithoutForeignKeySupportMustKeepExistingRelations() throws Exception {
        TestHarness harness = new TestHarness(root);
        harness.importFrom(new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id"), 1, null);
        assertEquals(1, harness.load().getRelations().size());

        // 「不支持读取 FK」不能被解释成「数据库没有 FK」
        FakeProvider noFkSupport = new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .withoutForeignKeySupport();
        harness.importFrom(noFkSupport, 1, null);

        assertEquals(1, harness.load().getRelations().size(), "不支持 FK 读取时必须保留旧关系");
    }

    private GraphWorkspace importSnapshot(Path base, int batchSize) throws Exception {
        TestHarness harness = new TestHarness(base);
        harness.importFrom(new FakeProvider()
                .table("orders", "id", "user_id")
                .table("users", "id")
                .foreignKey("orders", "user_id", "users", "id"), batchSize, null);
        return harness.load();
    }

    private static final class TestHarness {
        private final GraphWorkspaceStore store;
        private final WorkspaceImportService service;

        private TestHarness(Path root) {
            store = new GraphWorkspaceStore(root);
            service = new WorkspaceImportService(store, new WorkspaceImportJobStore(root),
                    new WorkspaceValidator(), new GraphWorkspaceMerger());
        }

        private ImportJob importFrom(FakeProvider provider, int batchSize, String tableFilter) throws Exception {
            ImportOptions options = new ImportOptions();
            options.setBatchSize(batchSize);
            options.setTableFilter(tableFilter);
            return service.start("unit", provider, options);
        }

        private GraphWorkspace load() throws Exception {
            return store.load("unit");
        }
    }

    private static final class FakeProvider implements WorkspaceMetadataProvider {
        private final Map<String, List<String>> tables = new LinkedHashMap<>();
        private final Set<String> schemas = new LinkedHashSet<>();
        private final List<ForeignKey> foreignKeys = new ArrayList<>();
        private final Set<String> failures = new java.util.HashSet<>();
        private final Set<GraphWorkspace> foreignKeyTargets = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        private boolean supportsForeignKeys = true;

        private FakeProvider withoutForeignKeySupport() {
            this.supportsForeignKeys = false;
            return this;
        }

        @Override
        public boolean supportsForeignKeys() {
            return supportsForeignKeys;
        }

        private FakeProvider table(String name, String... columns) {
            return tableInSchema("trade", name, columns);
        }

        private FakeProvider tableInSchema(String schema, String name, String... columns) {
            schemas.add(schema);
            tables.put(schema + "." + name, List.of(columns));
            return this;
        }

        private FakeProvider schema(String schema) {
            schemas.add(schema);
            return this;
        }

        private FakeProvider foreignKey(String fromTable, String fromColumn, String toTable, String toColumn) {
            return foreignKey("trade", fromTable, fromColumn, "trade", toTable, toColumn);
        }

        private FakeProvider foreignKey(String fromSchema, String fromTable, String fromColumn,
                String toSchema, String toTable, String toColumn) {
            foreignKeys.add(new ForeignKey(fromSchema, fromTable, fromColumn, toSchema, toTable, toColumn));
            return this;
        }

        private FakeProvider fail(String table) {
            failures.add(table);
            return this;
        }

        @Override
        public String getDatabaseType() {
            return "mysql";
        }

        @Override
        public String getProductName() {
            return "MySQL";
        }

        @Override
        public String getProductVersion() {
            return "8.4.0";
        }

        @Override
        public List<String> discoverSchemas() {
            return schemas.stream().sorted().toList();
        }

        @Override
        public List<String> discoverTables(String schemaName) {
            return tables.keySet().stream().filter(key -> key.startsWith(schemaName + "."))
                    .map(key -> key.substring(schemaName.length() + 1)).toList();
        }

        @Override
        public TableExtractResult extractTable(String schemaName, String tableName) throws SQLException {
            if (failures.contains(tableName)) {
                throw new SQLException("boom on " + tableName);
            }
            List<String> columns = tables.get(schemaName + "." + tableName);
            if (columns == null) {
                return null;
            }
            TableWorkspaceNode table = TableWorkspaceNode.create("unit", schemaName, tableName, GraphActor.extractor);
            table.setStatus(GraphStatus.partial);
            int ordinal = 1;
            for (String name : columns) {
                ColumnWorkspaceNode column = ColumnWorkspaceNode.create(name);
                column.setOrdinal(ordinal++);
                column.setPrimaryKey("id".equals(name));
                table.getColumns().add(column);
                if (column.isPrimaryKey()) {
                    table.getPrimaryKey().add(column.computeId("unit", schemaName, tableName));
                }
            }
            return new TableExtractResult(table);
        }

        @Override
        public void extractForeignKeysForTable(TableWorkspaceNode table, GraphWorkspace target) {
            foreignKeyTargets.add(target);
            foreignKeys.stream().filter(fk -> fk.fromSchema.equals(table.getSchema())
                    && fk.fromTable.equals(table.getName())).forEach(fk -> {
                TableWorkspaceNode toTable = target.getTableByQualifiedName(fk.toSchema + "." + fk.toTable);
                if (table.findColumn(fk.fromColumn) == null || toTable == null
                        || toTable.findColumn(fk.toColumn) == null) {
                    return;
                }
                RelationWorkspaceEdge relation = RelationWorkspaceEdge.create("unit",
                        RelationType.foreign_key,
                        GraphIds.columnId("unit", fk.fromSchema, fk.fromTable, fk.fromColumn),
                        GraphIds.columnId("unit", fk.toSchema, fk.toTable, fk.toColumn),
                        GraphActor.extractor);
                relation.setConfidence(1.0);
                relation.setVerified(true);
                target.getRelations().add(relation);
            });
        }

        private int foreignKeyTargetCount() {
            return foreignKeyTargets.size();
        }

        private record ForeignKey(String fromSchema, String fromTable, String fromColumn,
                String toSchema, String toTable, String toColumn) {
        }
    }
}
