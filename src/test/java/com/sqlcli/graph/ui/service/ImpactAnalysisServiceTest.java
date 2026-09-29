package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.LineageRecord;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactAnalysisServiceTest {

    private static final String ALIAS = "impact";

    @Test
    void relationImpactFindsMetricsAndTermScenariosThatWouldBreak() {
        GraphWorkspace workspace = seed();
        String ordersId = column("orders", "buyer_id");
        String usersId = column("users", "id");
        RelationWorkspaceEdge join = RelationWorkspaceEdge.create(
                ALIAS, RelationType.join_observed, ordersId, usersId, GraphActor.human);
        workspace.getRelations().add(join);

        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_by_user", GraphActor.human);
        metric.setExpression("SUM(orders.amount)");
        MetricRecord.MetricJoinStep step = new MetricRecord.MetricJoinStep();
        step.setRelationId(join.getId());
        step.setJoinType(MetricRecord.MetricJoinType.inner);
        metric.setJoinPath(List.of(step));
        metric.setDimensions(List.of(column("users", "id")));
        workspace.getMetrics().put(metric.getId(), metric);

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "用户订单", GraphActor.human);
        term.setPrimaryTarget(table("orders"));
        workspace.getTerms().put(term.getId(), term);
        RelationWorkspaceEdge mapping = RelationWorkspaceEdge.create(
                ALIAS, RelationType.term_mapping, term.getId(), table("users"), GraphActor.human);
        workspace.getRelations().add(mapping);

        ImpactAnalysisService service = new ImpactAnalysisService();
        ImpactAnalysisService.ImpactReport joinImpact = service.analyze(workspace, join.getId());
        assertEquals(1, joinImpact.breaking());
        assertTrue(joinImpact.impacts().stream()
                .anyMatch(item -> item.id().equals(metric.getId()) && item.dependency().equals("joinPath")));

        ImpactAnalysisService.ImpactReport mappingImpact = service.analyze(workspace, mapping.getId());
        assertEquals(1, mappingImpact.breaking());
        assertTrue(mappingImpact.impacts().stream()
                .anyMatch(item -> item.id().equals(term.getId()) && item.dependency().equals("term_mapping")));
    }

    @Test
    void metricImpactFindsTermsThatExplicitlyDependOnIt() {
        GraphWorkspace workspace = seed();
        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.human);
        metric.setExpression("SUM(orders.amount)");
        workspace.getMetrics().put(metric.getId(), metric);

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "已支付订单", GraphActor.human);
        term.setMetricRefs(new java.util.ArrayList<>(List.of(metric.getId())));
        workspace.getTerms().put(term.getId(), term);

        ImpactAnalysisService.ImpactReport report =
                new ImpactAnalysisService().analyze(workspace, metric.getId());

        assertEquals(1, report.breaking());
        assertTrue(report.impacts().stream().anyMatch(item ->
                item.kind().equals("term")
                        && item.id().equals(term.getId())
                        && item.dependency().equals("metricRefs")));
    }

    @Test
    void columnImpactTraversesMetricLineageAndRelations() {
        GraphWorkspace workspace = seed();
        String amount = column("orders", "amount");

        MetricRecord metric = MetricRecord.create(ALIAS, "gmv", GraphActor.human);
        metric.setExpression("SUM(orders.amount)");
        metric.setDimensions(List.of(amount));
        workspace.getMetrics().put(metric.getId(), metric);

        LineageRecord lineage = LineageRecord.create(ALIAS, column("users", "lifetime_value"),
                List.of(amount), "SUM(amount)", "etl.gmv", GraphActor.human);
        workspace.getLineage().put(lineage.getId(), lineage);

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(
                ALIAS, RelationType.join_observed, amount, column("users", "lifetime_value"), GraphActor.human);
        workspace.getRelations().add(relation);

        ImpactAnalysisService.ImpactReport report = new ImpactAnalysisService().analyze(workspace, amount);

        assertEquals(3, report.breaking());
        assertTrue(report.impacts().stream().anyMatch(item -> item.kind().equals("metric")));
        assertTrue(report.impacts().stream().anyMatch(item -> item.kind().equals("lineage")));
        assertTrue(report.impacts().stream().anyMatch(item -> item.kind().equals("relation")));
    }

    private static GraphWorkspace seed() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        for (String name : List.of("id", "buyer_id", "amount")) {
            orders.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        for (String name : List.of("id", "lifetime_value")) {
            users.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        workspace.getTables().put(users.getId(), users);
        return workspace;
    }

    private static String table(String name) {
        return GraphIds.tableId(ALIAS, "app", name);
    }

    private static String column(String table, String column) {
        return GraphIds.columnId(ALIAS, "app", table, column);
    }
}
