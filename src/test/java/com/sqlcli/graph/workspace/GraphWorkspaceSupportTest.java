package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.workspace.index.WorkspaceIndexManifest;
import com.sqlcli.graph.workspace.index.WorkspaceIndexStore;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import com.sqlcli.graph.workspace.index.IndexStatus;
import com.sqlcli.graph.workspace.diagram.WorkspaceDiagramGenerator;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import com.sqlcli.testsupport.Symlinks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphWorkspaceSupportTest {

    @Test
    void workspaceInitializationCreatesAliasPolicyLayout() throws Exception {
        Path root = Files.createTempDirectory("workspace-policy-layout-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            store.save(GraphWorkspace.create("unit", "mysql"));

            assertTrue(Files.isDirectory(root.resolve("unit/policy/rules")));
            assertEquals("ruleSets: []\n", Files.readString(
                    root.resolve("unit/policy/bindings.yaml")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void savedStatsShouldCountIgnoredRelationsSeparately() throws Exception {
        Path root = Files.createTempDirectory("workspace-stats-ignored-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");

            RelationWorkspaceEdge active = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                    "column:unit:trade.orders.user_id", "column:unit:user.users.id", GraphActor.agent);
            workspace.getRelations().add(active);
            RelationWorkspaceEdge ignored = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                    "column:unit:trade.orders.product_id", "column:unit:trade.products.id", GraphActor.agent);
            ignored.setStatus(GraphStatus.ignored);
            workspace.getRelations().add(ignored);

            store.save(workspace);

            // relations 是「存了什么」的结构事实，含 ignored；ignoredRelations 是旁边
            // 显式展示的那一份，两者并列，不互相抵消。
            assertEquals(2, workspace.getManifest().getStats().getRelations());
            assertEquals(1, workspace.getManifest().getStats().getIgnoredRelations());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void saveKeepsOnlyCurrentGeneration() throws Exception {
        Path root = Files.createTempDirectory("workspace-prune-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            store.save(workspace);
            workspace.getManifest().setRevision(workspace.getManifest().getRevision() + 1);
            store.save(workspace);
            workspace.getManifest().setRevision(workspace.getManifest().getRevision() + 1);
            store.save(workspace);

            Path generations = root.resolve("unit/generations");
            List<Path> remaining;
            try (var stream = Files.list(generations)) {
                remaining = stream.toList();
            }
            assertEquals(1, remaining.size(), "只保留当前代");
            String pointer = Files.readString(root.resolve("unit/current-generation")).trim();
            assertEquals(pointer, remaining.get(0).getFileName().toString());
            assertEquals(workspace.getManifest().getRevision(),
                    store.load("unit").getManifest().getRevision());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void workspaceInitializationRejectsLinkedPolicyRulesDirectory() throws Exception {
        Path root = Files.createTempDirectory("workspace-policy-link-");
        Path outside = Files.createTempDirectory("workspace-policy-outside-");
        try {
            Files.createDirectories(root.resolve("unit/policy"));
            Symlinks.createOrAbort(root.resolve("unit/policy/rules"), outside);

            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            assertThrows(IOException.class, () -> store.save(GraphWorkspace.create("unit", "mysql")));
        } finally {
            deleteTree(root);
            deleteTree(outside);
        }
    }

    @Test
    void termMappingShouldAllowTermToTableOrColumn() {
        RelationEndpointRule rule = RelationType.term_mapping.getEndpointRule();

        assertTrue(rule.isValidEndpoint(GraphObjectKind.term, GraphObjectKind.column));
        assertTrue(rule.isValidEndpoint(GraphObjectKind.term, GraphObjectKind.table));
        assertFalse(rule.isValidEndpoint(GraphObjectKind.column, GraphObjectKind.term));
        assertFalse(rule.isValidEndpoint(GraphObjectKind.term, GraphObjectKind.term));
        assertFalse(rule.isValidEndpoint(GraphObjectKind.column, GraphObjectKind.column));
    }

    @Test
    void relationIdShouldIncludeType() {
        String from = "column:unit:trade.orders.buyer_id";
        String to = "column:unit:user.users.id";

        String declared = GraphIds.relationId("unit", RelationType.foreign_key, from, to);
        String observed = GraphIds.relationId("unit", RelationType.join_observed, from, to);

        assertNotEquals(declared, observed);
        assertTrue(declared.startsWith("relation:unit:foreign_key:"));
    }

    @Test
    void termNodeShouldOnlyPersistDefinitionFields() throws Exception {
        TermWorkspaceNode term = TermWorkspaceNode.create("unit", "buyer", GraphActor.human);
        term.getAliases().add("purchaser");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(term);

        assertTrue(json.contains("\"name\":\"buyer\""));
        assertTrue(json.contains("\"aliases\":[\"purchaser\"]"));
        assertFalse(json.contains("mappedNodes"));
        assertFalse(json.contains("termRefs"));
    }

    @Test
    void validatorShouldReportDanglingRelation() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");

        TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        workspace.getTables().put(table.getId(), table);

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(
                "unit",
                RelationType.join_observed,
                "column:unit:trade.orders.user_id",
                "column:unit:user.users.id",
                GraphActor.agent);
        workspace.getRelations().add(relation);

        new WorkspaceValidator().validate(workspace);

        assertFalse(workspace.getValidationIssues().isEmpty());
        assertTrue(workspace.getValidationIssues().stream()
                .anyMatch(issue -> "dangling_relation_source".equals(issue.getCode())));
    }

    @Test
    void validatorShouldStillReportDuplicateRelationIdForIgnoredRelation() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        TableWorkspaceNode users = TableWorkspaceNode.create("unit", "user", "users", GraphActor.extractor);
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                "column:unit:trade.orders.user_id", "column:unit:user.users.id", GraphActor.agent);
        relation.setStatus(GraphStatus.ignored);
        // 手工构造一条 id 相同的第二条边（正常流程不会产生，这里模拟数据损坏），
        // duplicate_relation_id 是结构不变量，与 ignored 状态无关，必须照样报出来。
        RelationWorkspaceEdge duplicate = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                "column:unit:trade.orders.user_id", "column:unit:user.users.id", GraphActor.agent);
        duplicate.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(relation);
        workspace.getRelations().add(duplicate);

        new WorkspaceValidator().validate(workspace);

        assertTrue(workspace.getValidationIssues().stream()
                .anyMatch(issue -> "duplicate_relation_id".equals(issue.getCode())));
    }

    @Test
    void validatorShouldNotReportDanglingOrInvalidEndpointForIgnoredRelation() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
        // 故意不放任何表进图谱，让 from/to 都悬空——正常情况下会同时触发
        // dangling_relation_source/target，这里验证 ignored 边不该再报这类噪音。
        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                "column:unit:trade.orders.user_id", "column:unit:user.users.id", GraphActor.agent);
        relation.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(relation);

        new WorkspaceValidator().validate(workspace);

        assertTrue(workspace.getValidationIssues().stream()
                .noneMatch(issue -> "dangling_relation_source".equals(issue.getCode())
                        || "dangling_relation_target".equals(issue.getCode())
                        || "invalid_relation_endpoint".equals(issue.getCode())));
    }

    @Test
    void validatorShouldReportMetricJoinPathReferencingIgnoredRelation() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        TableWorkspaceNode users = TableWorkspaceNode.create("unit", "user", "users", GraphActor.extractor);
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                "column:unit:trade.orders.user_id", "column:unit:user.users.id", GraphActor.agent);
        relation.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(relation);

        MetricRecord metric = MetricRecord.create("unit", "gmv", GraphActor.human);
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId(relation.getId());
        metric.getJoinPath().add(step);
        workspace.getMetrics().put(metric.getId(), metric);

        new WorkspaceValidator().validate(workspace);

        assertTrue(workspace.getValidationIssues().stream()
                .anyMatch(issue -> "metric_joinpath_relation_ignored".equals(issue.getCode())));
        // 不是悬空——id 确实存在，只是被拒绝过，两个问题码不该同时报
        assertTrue(workspace.getValidationIssues().stream()
                .noneMatch(issue -> "dangling_metric_join_relation".equals(issue.getCode())));
    }

    @Test
    void validationCommitMustPersistErrorIssues() throws Exception {
        Path root = Files.createTempDirectory("workspace-validation-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            workspace.getRelations().add(RelationWorkspaceEdge.create(
                    "unit", RelationType.join_observed,
                    "column:unit:trade.orders.user_id", "column:unit:user.users.id", GraphActor.agent));
            store.save(workspace);
            WorkspaceValidator validator = new WorkspaceValidator();
            WorkspaceMutationService service = new WorkspaceMutationService(
                    store, validator, new WorkspaceLockManager());

            WorkspaceMutationService.MutationResult result = service.mutate(
                    "unit", workspace.getManifest().getRevision(), GraphActor.system, "validation", current -> {
                        validator.validate(current);
                        return new WorkspaceMutationService.MutationOutcome(
                                current.getManifest().getId(), ChangeOperation.verify);
                    });

            assertTrue(result.isSuccess());
            assertTrue(store.load("unit").getValidationIssues().stream()
                    .anyMatch(issue -> issue.getSeverity() == ValidationSeverity.error));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void searchShouldMatchTableAndColumnComment() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");

        TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        table.setComment("订单主表");
        workspace.getTables().put(table.getId(), table);

        ColumnWorkspaceNode column = ColumnWorkspaceNode.create("buyer_id");
        column.setComment("买家用户ID");
        table.getColumns().add(column);

        WorkspaceSearchEngine engine = new WorkspaceSearchEngine(workspace);
        assertEquals(1, engine.search("订单").stream().filter(r -> "table".equals(r.getType())).count());
        assertEquals(1, engine.search("买家").stream().filter(r -> "column".equals(r.getType())).count());
    }

    @Test
    void snapshotImportExportShouldPreserveObjects() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
        SchemaWorkspaceNode schema = SchemaWorkspaceNode.create("unit", "trade", GraphActor.extractor);
        workspace.getSchemas().put(schema.getId(), schema);
        workspace.getDataSource().setProductName("MySQL");
        workspace.getDataSource().setProductVersion("8.4.0");
        TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        TableIndexMetadata index = new TableIndexMetadata();
        index.setName("idx_orders_id");
        index.setColumns(List.of("id"));
        index.setUnique(true);
        table.getIndexes().add(index);
        workspace.getTables().put(table.getId(), table);
        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                table.getId(), table.getId(), GraphActor.agent);
        relation.setConfidence(0.7);
        relation.getEvidence().add(RelationEvidence.of("sql_scan", "queries.sql:3"));
        workspace.getRelations().add(relation);

        // 拒绝改成转状态而不是物理删除之后，export/import 这条纯搬运路径必须原样带过去
        // ignored 状态和拒绝原因——这是「必须不过滤」策略成立的证明：忽略的边要能存下来、
        // 能迁移，否则下次导入会把它当白纸重新生成一遍，「拒绝」这个动作就没意义了。
        RelationWorkspaceEdge ignoredRelation = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                table.getId(), schema.getId(), GraphActor.agent);
        ignoredRelation.setStatus(GraphStatus.ignored);
        ignoredRelation.getAttributes().put(
                WorkspaceMutationService.REJECTION_REASON_ATTR, "误判的join路径");
        workspace.getRelations().add(ignoredRelation);

        GraphWorkspaceStore store = new GraphWorkspaceStore();
        Path file = Files.createTempFile("workspace-", ".json");
        try {
            store.exportSnapshot(workspace, file);
            GraphWorkspace imported = store.importSnapshot(file);
            assertEquals("unit", imported.getManifest().getAlias());
            assertEquals(1, imported.getSchemas().size());
            assertEquals("8.4.0", imported.getDataSource().getProductVersion());
            assertEquals("idx_orders_id", imported.getTables().get(table.getId()).getIndexes().get(0).getName());
            assertEquals("queries.sql:3", imported.getRelations().get(0).getEvidence().get(0).getSourceRef());

            RelationWorkspaceEdge importedIgnored = imported.getRelations().stream()
                    .filter(r -> r.getId().equals(ignoredRelation.getId()))
                    .findFirst().orElseThrow();
            assertEquals(GraphStatus.ignored, importedIgnored.getStatus());
            assertEquals("误判的join路径", importedIgnored.getAttributes()
                    .get(WorkspaceMutationService.REJECTION_REASON_ATTR));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void snapshotImportShouldRejectMissingRevision() throws Exception {
        Path file = Files.createTempFile("workspace-invalid-", ".json");
        try {
            Files.writeString(file, """
                    {
                      "manifest": {
                        "modelVersion": 5,
                        "storageVersion": 5
                      }
                    }
                    """);
            GraphWorkspaceStore store = new GraphWorkspaceStore();
            IOException error = assertThrows(IOException.class, () -> store.importSnapshot(file));
            assertTrue(error.getMessage().contains("revision"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void loadsPreviousWorkspaceVersionAndUpgradesItInMemory() throws Exception {
        Path root = Files.createTempDirectory("workspace-v4-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            workspace.getManifest().setModelVersion(4);
            workspace.getManifest().setStorageVersion(4);
            store.save(workspace);

            GraphWorkspace loaded = store.load("unit");
            assertEquals(WorkspaceManifest.CURRENT_MODEL_VERSION, loaded.getManifest().getModelVersion());
            assertEquals(WorkspaceManifest.CURRENT_STORAGE_VERSION, loaded.getManifest().getStorageVersion());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void rebuiltIndexShouldSearchCjkAndTitle() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");

        TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        table.setComment("订单主表");
        workspace.getTables().put(table.getId(), table);

        WorkspaceIndexer indexer = new WorkspaceIndexer();
        var snapshot = indexer.rebuild(workspace);
        var hits = new WorkspaceIndexedSearchEngine(snapshot).search("订单");

        assertFalse(hits.isEmpty());
        assertEquals("table", hits.get(0).getType());
        assertEquals("orders", hits.get(0).getTable());
    }

    @Test
    void indexStatusBecomesStaleWhenWorkspaceRevisionChanges() throws Exception {
        Path root = Files.createTempDirectory("workspace-index-status-");
        try {
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
            workspace.getTables().put(table.getId(), table);
            WorkspaceIndexStore store = new WorkspaceIndexStore(root, 2048);
            store.save("unit", new WorkspaceIndexer().rebuild(workspace));
            assertEquals(IndexStatus.ready, store.getIndexStatus("unit", workspace.getManifest().getRevision()));
            workspace.getManifest().incrementRevision();
            assertEquals(IndexStatus.stale, store.getIndexStatus("unit", workspace.getManifest().getRevision()));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void indexStoreShouldPersistCompactShardsWithinSizeLimit() throws Exception {
        Path root = Files.createTempDirectory("workspace-index-");
        try {
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            for (int tableNo = 0; tableNo < 8; tableNo++) {
                String tableName = "orders_" + tableNo;
                TableWorkspaceNode table = TableWorkspaceNode.create(
                        "unit", "trade", tableName, GraphActor.extractor);
                table.setComment("订单业务主表 " + tableNo);
                workspace.getTables().put(table.getId(), table);
                for (int columnNo = 0; columnNo < 8; columnNo++) {
                    ColumnWorkspaceNode column = ColumnWorkspaceNode.create("buyer_" + columnNo);
                    column.setComment("买家字段 " + tableNo + "-" + columnNo);
                    table.getColumns().add(column);
                }
            }
            TermWorkspaceNode term = TermWorkspaceNode.create("unit", "buyer", GraphActor.human);
            term.setDisplayName("买家");
            term.getAliases().add("购买用户");
            workspace.getTerms().put(term.getId(), term);

            var snapshot = new WorkspaceIndexer().rebuild(workspace);
            int maxShardBytes = 2048;
            WorkspaceIndexStore store = new WorkspaceIndexStore(root, maxShardBytes);
            store.save("unit", snapshot);

            Path indexDir = root.resolve("unit/index");
            Path manifestPath = indexDir.resolve("manifest.json");
            assertTrue(Files.exists(manifestPath));
            WorkspaceIndexManifest manifest = store.loadManifest("unit");
            assertTrue(manifest.getShards().size() > 1);
            assertEquals(snapshot.getDocuments().size(), manifest.getDocumentCount());
            assertEquals(1, manifest.getTermCount());
            for (String shard : manifest.getShards()) {
                Path shardPath = indexDir.resolve(shard);
                assertTrue(Files.size(shardPath) <= maxShardBytes, shard);
                assertCompactJson(shardPath);
            }
            assertCompactJson(manifestPath);

            var loaded = store.load("unit");
            assertEquals(8, loaded.tableCount());
            assertEquals(64, loaded.columnCount());
            assertEquals(1, loaded.termCount());
            assertFalse(new WorkspaceIndexedSearchEngine(loaded).search("购买用户").isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void diagramGeneratorShouldSplitRelationsAndCatalogs() throws Exception {
        Path root = Files.createTempDirectory("workspace-diagram-");
        try {
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            SchemaWorkspaceNode schema = SchemaWorkspaceNode.create("unit", "trade", GraphActor.extractor);
            workspace.getSchemas().put(schema.getId(), schema);

            for (int i = 0; i < 205; i++) {
                TableWorkspaceNode table = TableWorkspaceNode.create(
                        "unit", "trade", "table_" + i, GraphActor.extractor);
                ColumnWorkspaceNode id = ColumnWorkspaceNode.create("id");
                table.getColumns().add(id);
                workspace.getTables().put(table.getId(), table);
            }
            for (int i = 0; i < 45; i++) {
                RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(
                        "unit",
                        RelationType.foreign_key,
                        GraphIds.columnId("unit", "trade", "table_" + i, "id"),
                        GraphIds.columnId("unit", "trade", "table_" + (i + 1), "id"),
                        GraphActor.extractor);
                relation.setVerified(true);
                workspace.getRelations().add(relation);
            }

            var result = new WorkspaceDiagramGenerator().generate(workspace, root);

            assertTrue(Files.exists(root.resolve("README.md")));
            assertTrue(Files.exists(root.resolve("overview.md")));
            assertTrue(Files.exists(root.resolve("manifest.json")));
            assertEquals(45, result.manifest().tableRelations);
            assertEquals(46, result.manifest().relatedTables);
            assertEquals(159, result.manifest().isolatedTables);
            assertTrue(result.manifest().files.stream()
                    .filter(file -> "relations".equals(file.kind)).count() > 1);
            assertEquals(2, result.manifest().files.stream()
                    .filter(file -> "catalog".equals(file.kind)).count());

            String relationGraph = Files.readString(root.resolve("relations-00001.md"));
            assertTrue(relationGraph.contains("```mermaid"));
            assertTrue(relationGraph.contains("foreign_key"));
            assertTrue(relationGraph.contains("id -> id"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void diagramGeneratorShouldExcludeIgnoredRelationsFromGraphAndCounts() throws Exception {
        Path root = Files.createTempDirectory("workspace-diagram-ignored-");
        try {
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            SchemaWorkspaceNode schema = SchemaWorkspaceNode.create("unit", "trade", GraphActor.extractor);
            workspace.getSchemas().put(schema.getId(), schema);

            TableWorkspaceNode orders = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
            orders.getColumns().add(ColumnWorkspaceNode.create("id"));
            workspace.getTables().put(orders.getId(), orders);
            TableWorkspaceNode users = TableWorkspaceNode.create("unit", "trade", "users", GraphActor.extractor);
            users.getColumns().add(ColumnWorkspaceNode.create("id"));
            workspace.getTables().put(users.getId(), users);

            RelationWorkspaceEdge active = RelationWorkspaceEdge.create("unit", RelationType.foreign_key,
                    GraphIds.columnId("unit", "trade", "orders", "id"),
                    GraphIds.columnId("unit", "trade", "users", "id"), GraphActor.extractor);
            workspace.getRelations().add(active);

            // 被拒绝的候选关系：画出来会误导人，静态 Mermaid 图没有「显示但标注」的余地，
            // 必须整条从图和计数里都消失，不只是从 mermaid 边里消失。
            RelationWorkspaceEdge ignored = RelationWorkspaceEdge.create("unit", RelationType.join_observed,
                    GraphIds.columnId("unit", "trade", "orders", "id"),
                    GraphIds.columnId("unit", "trade", "users", "id"), GraphActor.agent);
            ignored.setStatus(GraphStatus.ignored);
            workspace.getRelations().add(ignored);

            var result = new WorkspaceDiagramGenerator().generate(workspace, root);

            assertEquals(1, result.manifest().rawRelations, "manifest 的 rawRelations 只数非 ignored");
            assertEquals(1, result.manifest().tableRelations);

            String readme = Files.readString(root.resolve("README.md"));
            assertTrue(readme.contains("| Raw relations | 1 |"), "README 汇总表的 Raw relations 也只数非 ignored");

            String relationGraph = Files.readString(root.resolve("relations-00001.md"));
            assertTrue(relationGraph.contains("foreign_key"));
            assertFalse(relationGraph.contains("join_observed"), "被忽略的关系不应出现在生成的 Mermaid 图里");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void importShouldResumeFromFailedTableTask() throws Exception {
        Path root = Files.createTempDirectory("workspace-import-");
        try {
            GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(root);
            WorkspaceImportJobStore jobStore = new WorkspaceImportJobStore(root);
            WorkspaceImportService service = new WorkspaceImportService(workspaceStore, jobStore, new WorkspaceValidator(), new GraphWorkspaceMerger());

            ImportOptions options = new ImportOptions();
            options.setBatchSize(1);

            FakeProvider failingProvider = new FakeProvider(Set.of("users"));
            ImportJob failed = service.start("unit", failingProvider, options);
            assertEquals(ImportJobStatus.failed, failed.getStatus());

            GraphWorkspace partial = workspaceStore.load("unit");
            assertNotNull(partial.getTableByQualifiedName("trade.orders"));
            assertNull(partial.getTableByQualifiedName("trade.users"));

            FakeProvider resumeProvider = new FakeProvider(Set.of());
            ImportJob completed = service.resume("unit", resumeProvider);
            assertEquals(ImportJobStatus.completed, completed.getStatus());

            GraphWorkspace resumed = workspaceStore.load("unit");
            assertNotNull(resumed.getTableByQualifiedName("trade.orders"));
            assertNotNull(resumed.getTableByQualifiedName("trade.users"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void importShouldRecordAndSkipUnsupportedForeignKeys() throws Exception {
        Path root = Files.createTempDirectory("workspace-import-no-fk-");
        try {
            GraphWorkspaceStore workspaceStore = new GraphWorkspaceStore(root);
            WorkspaceImportJobStore jobStore = new WorkspaceImportJobStore(root);
            WorkspaceImportService service = new WorkspaceImportService(workspaceStore, jobStore,
                    new WorkspaceValidator(), new GraphWorkspaceMerger());
            FakeProvider provider = new FakeProvider(Set.of()) {
                @Override
                public boolean supportsForeignKeys() {
                    return false;
                }
            };

            ImportJob job = service.start("unit", provider, new ImportOptions());

            assertTrue(job.getStats().isForeignKeysSkipped());
            assertEquals(2, job.getStats().getTotalTasks());
            assertEquals(2, job.getStats().getCompletedTasks());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void structuredStoreShouldRoundTripInlineColumns() throws Exception {
        Path root = Files.createTempDirectory("workspace-structured-");
        try {
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            SchemaWorkspaceNode schema = SchemaWorkspaceNode.create("unit", "trade", GraphActor.extractor);
            workspace.getSchemas().put(schema.getId(), schema);
            TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
            workspace.getTables().put(table.getId(), table);
            ColumnWorkspaceNode column = ColumnWorkspaceNode.create("id");
            column.setOrdinal(1);
            table.getColumns().add(column);

            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            store.save(workspace);

            GraphWorkspace loaded = store.load("unit");
            assertEquals(1, loaded.getTables().size());
            assertNotNull(loaded.getTableByQualifiedName("trade.orders"));
            // Check column is inline in table
            TableWorkspaceNode loadedTable = loaded.getTableByQualifiedName("trade.orders");
            assertNotNull(loadedTable);
            assertEquals(1, loadedTable.getColumns().size());
            assertEquals("id", loadedTable.getColumns().get(0).getName());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void failedGenerationSaveMustLeavePreviousGenerationReadable() throws Exception {
        Path root = Files.createTempDirectory("workspace-generation-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            TableWorkspaceNode table = TableWorkspaceNode.create(
                    "unit", "trade", "orders", GraphActor.extractor);
            workspace.getTables().put(table.getId(), table);
            store.save(workspace);
            Path pointer = root.resolve("unit/current-generation");
            String previousGeneration = Files.readString(pointer);

            workspace.getManifest().incrementRevision();
            TermWorkspaceNode invalid = TermWorkspaceNode.create("unit", "bad/name", GraphActor.human);
            workspace.getTerms().put(invalid.getId(), invalid);
            assertThrows(IOException.class, () -> store.save(workspace));

            assertEquals(previousGeneration, Files.readString(pointer));
            GraphWorkspace loaded = store.load("unit");
            assertEquals(1, loaded.getManifest().getRevision());
            assertTrue(loaded.getTerms().isEmpty());
            assertNotNull(loaded.getTableByQualifiedName("trade.orders"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void legacyFlatWorkspaceMustRemainReadable() throws Exception {
        Path root = Files.createTempDirectory("workspace-legacy-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
            TableWorkspaceNode table = TableWorkspaceNode.create(
                    "unit", "trade", "orders", GraphActor.extractor);
            workspace.getTables().put(table.getId(), table);
            store.save(workspace);

            Path workspaceRoot = root.resolve("unit");
            Path pointer = workspaceRoot.resolve("current-generation");
            Path generation = workspaceRoot.resolve("generations")
                    .resolve(Files.readString(pointer).trim());
            for (String name : List.of(
                    "manifest.yaml", "datasource.yaml", "nodes", "edges", "changes", "validation")) {
                Files.move(generation.resolve(name), workspaceRoot.resolve(name));
            }
            Files.delete(pointer);

            GraphWorkspace loaded = store.load("unit");
            assertNotNull(loaded.getTableByQualifiedName("trade.orders"));
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignore) {
                }
            });
        }
    }

    private static void assertCompactJson(Path path) throws Exception {
        String content = Files.readString(path);
        assertFalse(content.contains(System.lineSeparator()), path.toString());
        assertFalse(content.contains("  \""), path.toString());
    }

    private static class FakeProvider implements WorkspaceMetadataProvider {
        private final Set<String> failTables;

        private FakeProvider(Set<String> failTables) {
            this.failTables = new HashSet<>(failTables);
        }

        @Override
        public String getDatabaseType() {
            return "mysql";
        }

        @Override
        public List<String> discoverSchemas() {
            return List.of("trade");
        }

        @Override
        public List<String> discoverTables(String schemaName) {
            return List.of("orders", "users");
        }

        @Override
        public TableExtractResult extractTable(String schemaName, String tableName) throws SQLException {
            if (failTables.contains(tableName)) {
                throw new SQLException("boom on " + tableName);
            }
            TableWorkspaceNode table = TableWorkspaceNode.create("unit", schemaName, tableName, GraphActor.extractor);

            ColumnWorkspaceNode id = ColumnWorkspaceNode.create("id");
            id.setOrdinal(1);
            id.setPrimaryKey(true);
            table.getColumns().add(id);
            String columnId = id.computeId("unit", schemaName, tableName);
            table.getPrimaryKey().add(columnId);

            return new TableExtractResult(table);
        }

        @Override
        public void extractForeignKeysForTable(TableWorkspaceNode table, GraphWorkspace target) {
        }
    }
}
