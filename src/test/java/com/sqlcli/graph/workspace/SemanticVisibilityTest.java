package com.sqlcli.graph.workspace;

import com.sqlcli.graph.workspace.index.WorkspaceIndexSnapshot;
import com.sqlcli.graph.workspace.index.WorkspaceIndexer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 被人工拒绝的语义对象必须退出所有消费面：实时搜索和持久索引保持同一条规则。
 */
class SemanticVisibilityTest {

    private static final String ALIAS = "semantic-visibility";

    @Test
    void ignoredTermsAndMetricsDisappearFromLiveAndIndexedSearch() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "已支付订单", GraphActor.agent);
        term.setDescription("只看已支付订单");
        workspace.getTerms().put(term.getId(), term);

        MetricRecord metric = MetricRecord.create(ALIAS, "gmv_paid", GraphActor.agent);
        metric.setBusinessName("已支付GMV");
        metric.setExpression("SUM(amount)");
        workspace.getMetrics().put(metric.getId(), metric);

        WorkspaceSearchEngine live = new WorkspaceSearchEngine(workspace);
        assertTrue(live.search("已支付订单").stream().anyMatch(row -> row.getId().equals(term.getId())));
        assertTrue(live.search("已支付GMV").stream().anyMatch(row -> row.getId().equals(metric.getId())));

        term.setStatus(GraphStatus.ignored);
        metric.setStatus(GraphStatus.ignored);

        assertFalse(live.search("已支付订单").stream().anyMatch(row -> row.getId().equals(term.getId())));
        assertFalse(live.search("已支付GMV").stream().anyMatch(row -> row.getId().equals(metric.getId())));

        WorkspaceIndexSnapshot index = new WorkspaceIndexer().rebuild(workspace);
        assertFalse(index.getDocuments().stream().anyMatch(doc -> doc.getId().equals(term.getId())));
        assertFalse(index.getDocuments().stream().anyMatch(doc -> doc.getId().equals(metric.getId())));
    }
}
