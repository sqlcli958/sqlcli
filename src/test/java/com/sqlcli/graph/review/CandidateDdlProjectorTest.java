package com.sqlcli.graph.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlcli.graph.policy.PolicyEnforcement;
import com.sqlcli.graph.policy.PolicyEvaluator;
import com.sqlcli.graph.policy.PolicyRule;
import com.sqlcli.graph.policy.PolicySeverity;
import com.sqlcli.graph.policy.RuleSet;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandidateDdlProjectorTest {
    @TempDir Path temp;

    @Test
    void createTableProjectsAnIsolatedCandidateAndPolicyEvaluatorAcceptsIt() {
        GraphWorkspace current = GraphWorkspace.create("ddl-review", "mysql");
        long revision = current.getManifest().getRevision();

        CandidateDdlProjector.ProjectionResult result = new CandidateDdlProjector().projectText(
                current,
                "CREATE TABLE app.orders ("
                        + "tenant_id BIGINT NOT NULL,"
                        + "order_no VARCHAR(32) NOT NULL,"
                        + "PRIMARY KEY (tenant_id, order_no),"
                        + "UNIQUE KEY uk_orders_no (order_no))",
                "inline.sql");

        assertTrue(result.diagnostics().isEmpty());
        TableWorkspaceNode candidateTable = result.workspace().getTableByQualifiedName("app.orders");
        assertNotNull(candidateTable);
        assertEquals(List.of("tenant_id", "order_no"),
                candidateTable.getColumns().stream().map(column -> column.getName()).toList());
        assertEquals(2, candidateTable.getPrimaryKey().size());
        assertTrue(candidateTable.getIndexes().stream().anyMatch(index ->
                index.isUnique() && index.getColumns().equals(List.of("order_no"))));
        assertEquals(1, result.changes().size());
        CandidateDdlProjector.ProjectionChange change = result.changes().get(0);
        assertEquals("CREATE", change.changeType());
        assertEquals("table", change.objectType());
        assertEquals("inline.sql:1", change.sourceRef());
        assertNull(change.before());
        assertNotNull(change.after());
        assertFalse(change.destructive());

        assertNull(current.getTableByQualifiedName("app.orders"));
        assertEquals(revision, current.getManifest().getRevision());
        assertEquals(revision, result.workspace().getManifest().getRevision());
        assertTrue(current.getChanges().isEmpty());
        assertTrue(result.workspace().getChanges().isEmpty());

        RuleSet rules = primaryKeyRules();
        assertTrue(new PolicyEvaluator().evaluate(
                result.workspace(), rules, List.of(), "evaluation-candidate").isEmpty());
    }

    @Test
    void utf8FileProjectsOrderedAlterIndexAndConstraintChangesWithLineReferences() throws Exception {
        GraphWorkspace current = GraphWorkspace.create("ddl-alter", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "ddl-alter", "app", "orders", com.sqlcli.graph.workspace.GraphActor.extractor);
        table.getColumns().add(com.sqlcli.graph.workspace.ColumnWorkspaceNode.create("id"));
        table.getColumns().add(com.sqlcli.graph.workspace.ColumnWorkspaceNode.create("legacy"));
        table.getColumns().add(com.sqlcli.graph.workspace.ColumnWorkspaceNode.create("code"));
        current.getTables().put(table.getId(), table);
        Path ddl = temp.resolve("变更.sql");
        Files.writeString(ddl, """
                ALTER TABLE app.orders ADD COLUMN status VARCHAR(16);
                ALTER TABLE app.orders MODIFY COLUMN code VARCHAR(64);
                ALTER TABLE app.orders RENAME COLUMN legacy TO legacy_code;
                ALTER TABLE app.orders DROP COLUMN legacy_code;
                ALTER TABLE app.orders ADD INDEX idx_orders_status (status);
                ALTER TABLE app.orders DROP INDEX idx_orders_status;
                ALTER TABLE app.orders ADD CONSTRAINT uk_orders_code UNIQUE (code);
                ALTER TABLE app.orders DROP CONSTRAINT uk_orders_code;
                """);

        CandidateDdlProjector.ProjectionResult result =
                new CandidateDdlProjector().projectFile(current, ddl);

        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        assertEquals(8, result.changes().size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                result.changes().stream()
                        .map(change -> Integer.parseInt(
                                change.sourceRef().substring(change.sourceRef().lastIndexOf(':') + 1)))
                        .toList());
        TableWorkspaceNode projected = result.workspace().getTableByQualifiedName("app.orders");
        assertNotNull(projected.findColumn("status"));
        assertEquals(64, projected.findColumn("code").getDataType().getLength());
        assertNull(projected.findColumn("legacy"));
        assertNull(projected.findColumn("legacy_code"));
        assertTrue(projected.getIndexes().isEmpty());
        assertEquals(List.of("ADD", "MODIFY", "RENAME", "DROP", "ADD", "DROP", "ADD", "DROP"),
                result.changes().stream().map(CandidateDdlProjector.ProjectionChange::changeType).toList());
        assertTrue(result.changes().stream()
                .filter(change -> "DROP".equals(change.changeType()))
                .allMatch(CandidateDdlProjector.ProjectionChange::destructive));
        assertTrue(current.getChanges().isEmpty());
        assertEquals(List.of("id", "legacy", "code"),
                current.getTableByQualifiedName("app.orders").getColumns().stream()
                        .map(column -> column.getName()).toList());
    }

    @Test
    void projectionIsDeterministicAndUnsupportedAmbiguousOrInvalidStatementsAreErrors() throws Exception {
        GraphWorkspace current = GraphWorkspace.create("ddl-errors", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "ddl-errors", "app", "orders", com.sqlcli.graph.workspace.GraphActor.extractor);
        table.getColumns().add(com.sqlcli.graph.workspace.ColumnWorkspaceNode.create("id"));
        current.getTables().put(table.getId(), table);
        String ddl = """
                CREATE TABLE app.audit_log (id BIGINT);
                SELECT 1;
                ALTER TABLE app.orders DROP COLUMN missing;
                CREATE TABLE broken (
                """;
        CandidateDdlProjector projector = new CandidateDdlProjector();

        CandidateDdlProjector.ProjectionResult first =
                projector.projectText(current, ddl, "invalid.sql");
        CandidateDdlProjector.ProjectionResult second =
                projector.projectText(current, ddl, "invalid.sql");

        assertEquals(1, first.changes().size());
        assertEquals(3, first.diagnostics().size());
        assertTrue(first.diagnostics().stream().allMatch(diagnostic ->
                "error".equals(diagnostic.severity())));
        assertEquals(List.of("invalid.sql:2", "invalid.sql:3", "invalid.sql:4"),
                first.diagnostics().stream()
                        .map(CandidateDdlProjector.ProjectionDiagnostic::sourceRef).toList());
        assertEquals(first.changes(), second.changes());
        assertEquals(first.workspace(), second.workspace());
        assertEquals(first.diagnostics(), second.diagnostics());
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        assertEquals(json.writeValueAsString(first), json.writeValueAsString(second));
        assertNull(current.getTableByQualifiedName("app.audit_log"));
        assertNotNull(current.getTableByQualifiedName("app.orders").findColumn("id"));
    }

    @Test
    void migrationProjectsDropTableAndMarksNarrowingNotNullChangesDestructive() throws Exception {
        GraphWorkspace current = GraphWorkspace.create("ddl-risk", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "ddl-risk", "app", "orders", com.sqlcli.graph.workspace.GraphActor.extractor);
        var code = com.sqlcli.graph.workspace.ColumnWorkspaceNode.create("code");
        code.getDataType().setRaw("VARCHAR(64)");
        code.getDataType().setNormalized("varchar");
        code.getDataType().setLength(64);
        table.getColumns().add(code);
        current.getTables().put(table.getId(), table);

        Path migration = temp.resolve("risk.sql");
        Files.writeString(migration, """
                ALTER TABLE app.orders MODIFY COLUMN code VARCHAR(16) NOT NULL;
                DROP TABLE app.orders;
                """);

        CandidateDdlProjector.ProjectionResult result =
                new CandidateDdlProjector().projectMigrationFile(current, migration);

        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        assertEquals(List.of("MODIFY", "DROP"), result.changes().stream()
                .map(CandidateDdlProjector.ProjectionChange::changeType).toList());
        assertTrue(result.changes().stream().allMatch(CandidateDdlProjector.ProjectionChange::destructive));
        assertEquals(List.of("column", "table"), result.changes().stream()
                .map(CandidateDdlProjector.ProjectionChange::objectType).toList());
        assertNull(result.workspace().getTableByQualifiedName("app.orders"));
        assertNotNull(current.getTableByQualifiedName("app.orders"));
    }

    @Test
    void createTableCommentIsProjectedAndGrainMarkerIsExtractedFromTableComment() {
        GraphWorkspace current = GraphWorkspace.create("ddl-comment", "mysql");

        CandidateDdlProjector.ProjectionResult result = new CandidateDdlProjector().projectText(
                current,
                "CREATE TABLE app.inspection_plan ("
                        + "id BIGINT NOT NULL COMMENT '主键',"
                        + "status VARCHAR(20) COMMENT '状态 | 已废弃',"
                        + "PRIMARY KEY (id)"
                        + ") COMMENT '巡检计划 | grain=一行一个巡检计划'",
                "inline.sql");

        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        TableWorkspaceNode table = result.workspace().getTableByQualifiedName("app.inspection_plan");
        assertNotNull(table);
        assertEquals("巡检计划", table.getComment());
        assertEquals("一行一个巡检计划", table.getGrain());
        assertEquals("主键", table.findColumn("id").getComment());
        // 列注释里的 "| 已废弃" 没有 grain= 前缀，认不出来就整条原样当 comment，不做拆分
        assertEquals("状态 | 已废弃", table.findColumn("status").getComment());
    }

    @Test
    void alterModifyColumnCommentIsUpdatedButUnmarkedCommentsAreNeverSplit() throws Exception {
        GraphWorkspace current = GraphWorkspace.create("ddl-comment-alter", "mysql");
        TableWorkspaceNode table = TableWorkspaceNode.create(
                "ddl-comment-alter", "app", "orders", com.sqlcli.graph.workspace.GraphActor.extractor);
        table.getColumns().add(com.sqlcli.graph.workspace.ColumnWorkspaceNode.create("code"));
        current.getTables().put(table.getId(), table);

        CandidateDdlProjector.ProjectionResult result = new CandidateDdlProjector().projectText(
                current,
                "ALTER TABLE app.orders MODIFY COLUMN code VARCHAR(32) COMMENT '订单号,冗余存储'",
                "inline.sql");

        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        // 分隔符只认 |，逗号不触发拆分
        assertEquals("订单号,冗余存储",
                result.workspace().getTableByQualifiedName("app.orders").findColumn("code").getComment());
    }

    private RuleSet primaryKeyRules() {
        PolicyRule rule = new PolicyRule();
        rule.setId("primary-key");
        rule.setTitle("Primary key");
        rule.setCategory("primary_key_required");
        rule.setSeverity(PolicySeverity.error);
        rule.setEnforcement(PolicyEnforcement.required);
        RuleSet rules = new RuleSet();
        rules.setKind("PolicyRuleSet");
        rules.setId("candidate");
        rules.setTitle("Candidate");
        rules.setVersion("1");
        rules.getRules().add(rule);
        return rules;
    }
}
