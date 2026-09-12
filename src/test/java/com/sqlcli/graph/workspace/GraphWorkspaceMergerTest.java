package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphWorkspaceMergerTest {

    private GraphWorkspaceMerger merger;

    @BeforeEach
    void setUp() {
        merger = new GraphWorkspaceMerger();
    }

    private GraphWorkspace createWorkspace(String alias) {
        return GraphWorkspace.create(alias, "mysql");
    }

    private TableWorkspaceNode createTable(String alias, String schema, String tableName) {
        TableWorkspaceNode table = TableWorkspaceNode.create(alias, schema, tableName, GraphActor.extractor);
        table.setComment("db comment");
        table.setTableType(TableType.base_table);
        table.setStatus(GraphStatus.partial);
        return table;
    }

    private ColumnWorkspaceNode createColumn(String name, int ordinal) {
        ColumnWorkspaceNode col = ColumnWorkspaceNode.create(name);
        col.setOrdinal(ordinal);
        col.setComment("db col comment");
        col.getDataType().setRaw("VARCHAR");
        col.getDataType().setNormalized("varchar");
        col.setNullable(true);
        col.setPrimaryKey(ordinal == 1);
        return col;
    }

    @Nested
    @DisplayName("首次导入（空 existing workspace）")
    class FirstImport {

        @Test
        @DisplayName("incoming 全部添加到 existing")
        void firstImportAddsAll() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode table = createTable("unit", "trade", "orders");
            table.getColumns().add(createColumn("id", 1));
            incoming.getTables().put(table.getId(), table);

            GraphWorkspace result = merger.merge(incoming, existing);

            assertEquals(1, result.getTables().size());
            assertNotNull(result.getTables().get(table.getId()));
        }
    }

    @Nested
    @DisplayName("重新导入保护用户字段")
    class RefreshImport {

        @Test
        @DisplayName("用户 businessName 不被覆盖")
        void preservesBusinessName() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            // existing: 用户维护了 businessName
            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.setBusinessName("订单表");
            existingTable.getColumns().add(createColumn("id", 1));
            existing.getTables().put(existingTable.getId(), existingTable);

            // incoming: 没有业务名称
            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.getColumns().add(createColumn("id", 1));
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            assertEquals("订单表", result.getTables().get(existingTable.getId()).getBusinessName());
        }

        @Test
        @DisplayName("系统字段（tableType, system）从 incoming 更新")
        void updatesSystemFields() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.setTableType(TableType.unknown);
            existingTable.setSystem(true);
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.setTableType(TableType.view);
            incomingTable.setSystem(false);
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            TableWorkspaceNode merged = result.getTables().get(existingTable.getId());
            assertEquals(TableType.view, merged.getTableType());
            assertFalse(merged.isSystem());
        }

        @Test
        @DisplayName("用户 tags 不被覆盖")
        void preservesUserTags() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.getTags().add("core");
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            TableWorkspaceNode merged = result.getTables().get(existingTable.getId());
            assertEquals(List.of("core"), merged.getTags());
        }
    }

    @Nested
    @DisplayName("comment 永远覆盖，description 走用户字段规则")
    class CommentAndDescriptionHandling {

        @Test
        @DisplayName("comment 是数据库注释镜像，incoming 永远覆盖 existing")
        void commentAlwaysOverwritten() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.setComment("old db comment");
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.setComment("new db comment");
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            assertEquals("new db comment", result.getTables().get(existingTable.getId()).getComment());
        }

        @Test
        @DisplayName("重导入会更新 comment，但不会动 description（表）")
        void reimportUpdatesCommentButNotDescription() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.setComment("old db comment");
            existingTable.setDescription("人工写的业务描述");
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.setComment("new db comment");
            // incoming（DB 扫描）从不产生 description
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            TableWorkspaceNode merged = result.getTables().get(existingTable.getId());
            assertEquals("new db comment", merged.getComment());
            assertEquals("人工写的业务描述", merged.getDescription());
        }

        @Test
        @DisplayName("重导入会更新 comment，但不会动 description（列）")
        void reimportUpdatesColumnCommentButNotDescription() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            ColumnWorkspaceNode col = createColumn("phone", 1);
            col.setComment("旧注释");
            col.setDescription("人工写的字段说明");
            existingTable.getColumns().add(col);
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            ColumnWorkspaceNode incomingCol = createColumn("phone", 1);
            incomingCol.setComment("手机号");
            incomingTable.getColumns().add(incomingCol);
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            ColumnWorkspaceNode mergedCol = result.getTables().get(existingTable.getId()).findColumn("phone");
            assertEquals("手机号", mergedCol.getComment());
            assertEquals("人工写的字段说明", mergedCol.getDescription());
        }

        @Test
        @DisplayName("existing 没写 description 时 incoming 的 description 可以补进来（export/import 场景）")
        void descriptionFillsWhenExistingBlank() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.setDescription("快照里带的业务描述");
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            assertEquals("快照里带的业务描述", result.getTables().get(existingTable.getId()).getDescription());
        }
    }

    @Nested
    @DisplayName("列变更检测")
    class ColumnChanges {

        @Test
        @DisplayName("新增列从 incoming 添加")
        void addsNewColumn() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.getColumns().add(createColumn("id", 1));
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.getColumns().add(createColumn("id", 1));
            incomingTable.getColumns().add(createColumn("name", 2));
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            TableWorkspaceNode merged = result.getTables().get(existingTable.getId());
            assertEquals(2, merged.getColumns().size());
            assertNotNull(merged.findColumn("name"));
        }

        @Test
        @DisplayName("DB 中删除的列标记为 deprecated")
        void marksDeprecatedColumn() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            existingTable.getColumns().add(createColumn("id", 1));
            existingTable.getColumns().add(createColumn("old_col", 2));
            existing.getTables().put(existingTable.getId(), existingTable);

            // incoming 中没有 old_col（DB 中已删除）
            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            incomingTable.getColumns().add(createColumn("id", 1));
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            ColumnWorkspaceNode oldCol = result.getTables().get(existingTable.getId()).findColumn("old_col");
            assertNotNull(oldCol);
            assertEquals(true, oldCol.getAttributes().get("deprecated"));
        }

        @Test
        @DisplayName("列的系统字段从 DB 更新，用户字段保留")
        void updatesSystemFieldsPreservesUserFields() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "orders");
            ColumnWorkspaceNode col = createColumn("amount", 1);
            col.setBusinessName("金额");
            existingTable.getColumns().add(col);
            existing.getTables().put(existingTable.getId(), existingTable);

            TableWorkspaceNode incomingTable = createTable("unit", "trade", "orders");
            ColumnWorkspaceNode incomingCol = createColumn("amount", 1);
            incomingCol.getDataType().setRaw("DECIMAL");
            incomingCol.setNullable(false);
            incomingCol.setIndexed(true);
            incomingCol.setUnique(true);
            incomingCol.getAttributes().put("onUpdate", "CURRENT_TIMESTAMP");
            incomingTable.getColumns().add(incomingCol);
            incoming.getTables().put(incomingTable.getId(), incomingTable);

            GraphWorkspace result = merger.merge(incoming, existing);

            ColumnWorkspaceNode mergedCol = result.getTables().get(existingTable.getId()).findColumn("amount");
            // 系统字段更新
            assertEquals("DECIMAL", mergedCol.getDataType().getRaw());
            assertFalse(mergedCol.isNullable());
            assertTrue(mergedCol.isIndexed());
            assertTrue(mergedCol.isUnique());
            assertEquals("CURRENT_TIMESTAMP", mergedCol.getAttributes().get("onUpdate"));
            // 用户字段保留
            assertEquals("金额", mergedCol.getBusinessName());
        }
    }

    @Nested
    @DisplayName("关系合并")
    class RelationMerge {

        @Test
        @DisplayName("FK 声明：incoming 替换 existing")
        void fkDeclaredReplacesExisting() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            // existing 中的旧 FK
            RelationWorkspaceEdge oldFk = RelationWorkspaceEdge.create("unit",
                    RelationType.foreign_key,
                    "column:unit:trade.orders.user_id", "column:unit:trade.users.id",
                    GraphActor.extractor);
            oldFk.setJoinExpression("orders.user_id = users.id");
            existing.getRelations().add(oldFk);

            // incoming 中的新 FK（不同 join expression）
            RelationWorkspaceEdge newFk = RelationWorkspaceEdge.create("unit",
                    RelationType.foreign_key,
                    "column:unit:trade.orders.user_id", "column:unit:trade.users.id",
                    GraphActor.extractor);
            newFk.setJoinExpression("orders.user_id = users.id");
            incoming.getRelations().add(newFk);

            GraphWorkspace result = merger.merge(incoming, existing);

            // 应该只有一条 FK 关系
            long fkCount = result.getRelations().stream()
                    .filter(r -> r.getType() == RelationType.foreign_key)
                    .count();
            assertEquals(1, fkCount);
        }

        @Test
        @DisplayName("非 FK 关系：existing wins")
        void preservesNonFkRelations() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            // 用户创建的推断关系
            RelationWorkspaceEdge inferred = RelationWorkspaceEdge.create("unit",
                    RelationType.join_observed,
                    "column:unit:trade.orders.ref_id", "column:unit:trade.refs.id",
                    GraphActor.human);
            inferred.setConfidence(0.8);
            existing.getRelations().add(inferred);

            // incoming 中有同 ID 但不同类型（不应替换）
            RelationWorkspaceEdge incomingRel = RelationWorkspaceEdge.create("unit",
                    RelationType.join_observed,
                    "column:unit:trade.orders.ref_id", "column:unit:trade.refs.id",
                    GraphActor.extractor);
            incomingRel.setConfidence(0.9);
            incoming.getRelations().add(incomingRel);

            GraphWorkspace result = merger.merge(incoming, existing);

            // 非 FK 关系保留 existing，但更新 confidence（incoming 更高）
            RelationWorkspaceEdge merged = result.getRelations().stream()
                    .filter(r -> r.getType() == RelationType.join_observed)
                    .findFirst().orElse(null);
            assertNotNull(merged);
            assertEquals(0.9, merged.getConfidence());
        }

        @Test
        @DisplayName("混合关系：FK 替换 + 非 FK 保留")
        void mixedRelations() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            // existing: 1 FK + 1 inferred
            RelationWorkspaceEdge existingFk = RelationWorkspaceEdge.create("unit",
                    RelationType.foreign_key,
                    "column:unit:trade.orders.user_id", "column:unit:trade.users.id",
                    GraphActor.extractor);
            existing.getRelations().add(existingFk);

            RelationWorkspaceEdge inferred = RelationWorkspaceEdge.create("unit",
                    RelationType.join_observed,
                    "column:unit:trade.orders.product_id", "column:unit:trade.products.id",
                    GraphActor.agent);
            inferred.setConfidence(0.6);
            existing.getRelations().add(inferred);

            // incoming: 1 new FK
            RelationWorkspaceEdge newFk = RelationWorkspaceEdge.create("unit",
                    RelationType.foreign_key,
                    "column:unit:trade.orders.user_id", "column:unit:trade.users.id",
                    GraphActor.extractor);
            incoming.getRelations().add(newFk);

            GraphWorkspace result = merger.merge(incoming, existing);

            // join_observed 保留
            assertTrue(result.getRelations().stream()
                    .anyMatch(r -> r.getType() == RelationType.join_observed));
            // FK 只有一条
            assertEquals(1, result.getRelations().stream()
                    .filter(r -> r.getType() == RelationType.foreign_key)
                    .count());
        }

        @Test
        @DisplayName("重导入不会把 ignored 冲回 candidate")
        void reimportKeepsIgnoredStatus() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            // existing：一条已被人拒绝过的候选关系
            RelationWorkspaceEdge ignored = RelationWorkspaceEdge.create("unit",
                    RelationType.join_observed,
                    "column:unit:trade.orders.ref_id", "column:unit:trade.refs.id",
                    GraphActor.agent);
            ignored.setStatus(GraphStatus.ignored);
            ignored.setConfidence(0.6);
            ignored.getAttributes().put("rejectionReason", "误判");
            existing.getRelations().add(ignored);

            // incoming：挖掘任务重新发现同一条候选，同 id，带更高置信度和 verified=true
            RelationWorkspaceEdge rediscovered = RelationWorkspaceEdge.create("unit",
                    RelationType.join_observed,
                    "column:unit:trade.orders.ref_id", "column:unit:trade.refs.id",
                    GraphActor.agent);
            rediscovered.setConfidence(0.95);
            rediscovered.setVerified(true);
            incoming.getRelations().add(rediscovered);

            GraphWorkspace result = merger.merge(incoming, existing);

            RelationWorkspaceEdge merged = result.getRelations().stream()
                    .filter(r -> r.getId().equals(ignored.getId()))
                    .findFirst().orElseThrow();
            // mergeRelationFields 只动 confidence/verified/joinExpression/evidence，从不动 status
            assertEquals(GraphStatus.ignored, merged.getStatus());
            assertEquals(0.95, merged.getConfidence());
            // 副作用防线：即使 confidence 被推高到 >= 0.9 阈值，ignored 边也不该被
            // 顺带打上 verified=true——那是人从没设过的标记，会在撤销忽略时显得莫名其妙。
            assertFalse(Boolean.TRUE.equals(merged.getVerified()));
            assertEquals("误判", merged.getAttributes().get("rejectionReason"));
        }
    }

    @Nested
    @DisplayName("删除表检测")
    class DeprecatedTableDetection {

        @Test
        @DisplayName("导入范围内的表不在 incoming 中标记为 deprecated")
        void marksDeprecatedTable() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode existingTable = createTable("unit", "trade", "old_table");
            existing.getTables().put(existingTable.getId(), existingTable);

            // incoming 中没有 old_table
            merger.merge(incoming, existing, Set.of("trade"));

            assertEquals(GraphStatus.deprecated, existing.getTables().get(existingTable.getId()).getStatus());
        }

        @Test
        @DisplayName("不在导入范围内的表不被标记")
        void doesNotMarkOutOfScopeTable() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            TableWorkspaceNode otherSchemaTable = createTable("unit", "other", "some_table");
            existing.getTables().put(otherSchemaTable.getId(), otherSchemaTable);

            merger.merge(incoming, existing, Set.of("trade"));

            assertNotEquals(GraphStatus.deprecated, existing.getTables().get(otherSchemaTable.getId()).getStatus());
        }
    }

    @Nested
    @DisplayName("Schemas 合并")
    class SchemaMerge {

        @Test
        @DisplayName("Schema putIfAbsent 不覆盖已有")
        void schemaPutIfAbsent() {
            GraphWorkspace existing = createWorkspace("unit");
            GraphWorkspace incoming = createWorkspace("unit");

            SchemaWorkspaceNode existingSchema = SchemaWorkspaceNode.create("unit", "trade", GraphActor.human);
            existingSchema.setDescription("我的业务域");
            existing.getSchemas().put(existingSchema.getId(), existingSchema);

            SchemaWorkspaceNode incomingSchema = SchemaWorkspaceNode.create("unit", "trade", GraphActor.extractor);
            incoming.getSchemas().put(incomingSchema.getId(), incomingSchema);

            GraphWorkspace result = merger.merge(incoming, existing);

            assertEquals("我的业务域", result.getSchemas().get(existingSchema.getId()).getDescription());
        }

    }
}
