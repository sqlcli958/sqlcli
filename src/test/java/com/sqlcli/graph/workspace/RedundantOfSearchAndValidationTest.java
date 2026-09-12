package com.sqlcli.graph.workspace;

import com.sqlcli.graph.workspace.index.WorkspaceIndexDocument;
import com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import com.sqlcli.graph.workspace.index.WorkspaceIndexSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 函数依赖 / 权威来源标注（{@link ColumnWorkspaceNode#ATTR_REDUNDANT_OF}）的两个消费端：
 * 检索命中冗余列时要带出权威源，图谱校验要能抓出悬空/自指的权威源。
 *
 * <p>标注本身只是 attributes 里的一个 key（见 {@link ColumnWorkspaceNode} javadoc），
 * 这里不重复测"能不能写进 attributes"——那是 CLI 层
 * {@code SchemaEditRedundantOfTest} 的事，这里测的是写进去之后两条消费路径有没有接上。
 */
class RedundantOfSearchAndValidationTest {

    @Test
    void realtimeSearchCarriesRedundantOfOnColumnHit() {
        GraphWorkspace workspace = workspaceWithRedundantColumn();

        List<WorkspaceSearchEngine.SearchResult> results = new WorkspaceSearchEngine(workspace).search("customer_name");

        WorkspaceSearchEngine.SearchResult hit = results.stream()
                .filter(r -> "column".equals(r.getType()) && "customer_name".equals(r.getColumnName()))
                .findFirst().orElseThrow();
        assertTrue(hit.getRedundantOf().endsWith("app.customer.name"),
                "expected redundantOf to point at customer.name, got: " + hit.getRedundantOf());
    }

    @Test
    void nonRedundantColumnHitHasNullRedundantOf() {
        GraphWorkspace workspace = workspaceWithRedundantColumn();

        List<WorkspaceSearchEngine.SearchResult> results = new WorkspaceSearchEngine(workspace).search("name");

        WorkspaceSearchEngine.SearchResult authoritative = results.stream()
                .filter(r -> "column".equals(r.getType()) && "customer".equals(r.getTableName()))
                .findFirst().orElseThrow();
        assertNull(authoritative.getRedundantOf());
    }

    @Test
    void indexedSearchCarriesRedundantOfThroughRebuild() {
        GraphWorkspace workspace = workspaceWithRedundantColumn();
        WorkspaceIndexSnapshot snapshot = new WorkspaceIndexer().rebuild(workspace);

        WorkspaceIndexDocument doc = snapshot.getDocuments().stream()
                .filter(d -> "column".equals(d.getType()) && "customer_name".equals(d.getColumn()))
                .findFirst().orElseThrow();
        assertTrue(doc.getRedundantOf().endsWith("app.customer.name"));

        List<WorkspaceIndexedSearchEngine.SearchHit> hits = new WorkspaceIndexedSearchEngine(snapshot).search("customer_name");
        WorkspaceIndexedSearchEngine.SearchHit hit = hits.stream()
                .filter(h -> "column".equals(h.getType()) && "customer_name".equals(h.getColumn()))
                .findFirst().orElseThrow();
        assertTrue(hit.getRedundantOf().endsWith("app.customer.name"));
    }

    @Test
    void validatorFlagsDanglingRedundantSource() {
        GraphWorkspace workspace = workspaceWithRedundantColumn();
        TableWorkspaceNode orders = workspace.getTableByQualifiedName("app.orders");
        orders.findColumn("customer_name").getAttributes()
                .put(ColumnWorkspaceNode.ATTR_REDUNDANT_OF, "column:commands:app.customer.does_not_exist");

        new WorkspaceValidator().validate(workspace);

        assertTrue(workspace.getValidationIssues().stream()
                .anyMatch(issue -> "dangling_redundant_source".equals(issue.getCode())));
    }

    @Test
    void validatorFlagsSelfReferencingRedundantSource() {
        GraphWorkspace workspace = workspaceWithRedundantColumn();
        TableWorkspaceNode orders = workspace.getTableByQualifiedName("app.orders");
        ColumnWorkspaceNode customerName = orders.findColumn("customer_name");
        customerName.getAttributes().put(ColumnWorkspaceNode.ATTR_REDUNDANT_OF,
                customerName.computeId("commands", "app", "orders"));

        new WorkspaceValidator().validate(workspace);

        assertTrue(workspace.getValidationIssues().stream()
                .anyMatch(issue -> "self_redundant_source".equals(issue.getCode())));
    }

    @Test
    void validatorIsCleanWhenNoRedundantAnnotations() {
        GraphWorkspace workspace = workspaceWithRedundantColumn();
        workspace.getTableByQualifiedName("app.orders").findColumn("customer_name")
                .getAttributes().remove(ColumnWorkspaceNode.ATTR_REDUNDANT_OF);

        new WorkspaceValidator().validate(workspace);

        assertEquals(0, workspace.getValidationIssues().stream()
                .filter(issue -> issue.getCode().contains("redundant")).count());
    }

    private GraphWorkspace workspaceWithRedundantColumn() {
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        ColumnWorkspaceNode customerName = ColumnWorkspaceNode.create("customer_name");
        String customerNameId = "column:commands:app.customer.name";
        customerName.getAttributes().put(ColumnWorkspaceNode.ATTR_REDUNDANT_OF, customerNameId);
        orders.getColumns().add(customerName);

        TableWorkspaceNode customer = TableWorkspaceNode.create(
                "commands", "app", "customer", GraphActor.extractor);
        customer.getColumns().add(ColumnWorkspaceNode.create("name"));

        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(customer.getId(), customer);
        return workspace;
    }
}
