package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 dev-checklist P1 第 5 条：joinExpression 长期只写不读，这里验证
 * WorkspacePathFinder 的路径输出真的把它（连同 optionality）带出来了，
 * 且反向边（BFS 内部临时构造，见 buildAdjacency）不会把这两样信息弄丢或弄反。
 */
class WorkspacePathFinderTest {

    private GraphWorkspace baseWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("order_no"));
        TableWorkspaceNode master = TableWorkspaceNode.create("unit", "trade", "order_master", GraphActor.extractor);
        master.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        master.getColumns().add(ColumnWorkspaceNode.create("order_no"));
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(master.getId(), master);
        return workspace;
    }

    private RelationWorkspaceEdge compositeFkEdge(String from, String to) {
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create("unit", RelationType.foreign_key, from, to,
                GraphActor.extractor);
        edge.setJoinExpression("trade.orders.tenant_id = trade.order_master.tenant_id"
                + " AND trade.orders.order_no = trade.order_master.order_no");
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_GROUP, "fk_orders_order_master");
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, false);
        return edge;
    }

    @Test
    @DisplayName("正向路径携带完整 joinExpression 与两端 optionality")
    void forwardPathCarriesJoinExpressionAndOptionality() {
        GraphWorkspace workspace = baseWorkspace();
        workspace.getRelations().add(compositeFkEdge(
                "column:unit:trade.orders.tenant_id", "column:unit:trade.order_master.tenant_id"));

        List<WorkspacePathFinder.PathResult> paths =
                new WorkspacePathFinder(workspace).findPaths("trade.orders", "trade.order_master");

        assertEquals(1, paths.size());
        WorkspacePathFinder.PathSegment segment = paths.get(0).getPath().get(0);
        assertEquals("trade.orders.tenant_id = trade.order_master.tenant_id"
                + " AND trade.orders.order_no = trade.order_master.order_no", segment.getJoinExpression());
        assertEquals(Boolean.TRUE, segment.getFromOptional());
        assertEquals(Boolean.FALSE, segment.getToOptional());
    }

    @Test
    @DisplayName("反向路径的 joinExpression 原样带出，optionality 跟着 from/to 一起翻转")
    void reversePathFlipsOptionalityButKeepsJoinExpression() {
        GraphWorkspace workspace = baseWorkspace();
        workspace.getRelations().add(compositeFkEdge(
                "column:unit:trade.orders.tenant_id", "column:unit:trade.order_master.tenant_id"));

        // 反着找：从 order_master 到 orders，走的是 buildAdjacency 里临时构造的反向边
        List<WorkspacePathFinder.PathResult> paths =
                new WorkspacePathFinder(workspace).findPaths("trade.order_master", "trade.orders");

        assertEquals(1, paths.size());
        WorkspacePathFinder.PathSegment segment = paths.get(0).getPath().get(0);
        assertEquals("trade.orders.tenant_id = trade.order_master.tenant_id"
                + " AND trade.orders.order_no = trade.order_master.order_no", segment.getJoinExpression());
        // 正向边 fromOptional=true/toOptional=false，反向边应互换
        assertEquals(Boolean.FALSE, segment.getFromOptional());
        assertEquals(Boolean.TRUE, segment.getToOptional());
    }

    @Test
    @DisplayName("被忽略的关系不会被选进路径——拒绝过的知识不能反过来影响生成的 SQL")
    void ignoredRelationIsExcludedFromPath() {
        GraphWorkspace workspace = baseWorkspace();
        RelationWorkspaceEdge ignored = compositeFkEdge(
                "column:unit:trade.orders.tenant_id", "column:unit:trade.order_master.tenant_id");
        ignored.setStatus(GraphStatus.ignored);
        workspace.getRelations().add(ignored);

        List<WorkspacePathFinder.PathResult> paths =
                new WorkspacePathFinder(workspace).findPaths("trade.orders", "trade.order_master");

        assertTrue(paths.isEmpty(), "ignored 边不应作为可用的 JOIN 路线被选中");
    }
}
