package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.ui.dto.GraphViewDto;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 画布数据必须把 {@link GraphStatus#ignored} 的关系当不存在——
 * 拒绝候选不再删边（P1 第 6 条）之后，图谱页要是照旧把它画出来，等于让人拒绝的判断
 * 在画布上继续生效。策略见 {@link WorkspaceMutationService#isIgnored} 的方法注释。
 */
class GraphViewServiceIgnoredRelationTest {

    private static final String ALIAS = "canvas-ignore";

    @Test
    void ignoredRelationIsExcludedFromEdgesAndRelationCount() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("customer_id"));
        workspace.getTables().put(orders.getId(), orders);
        TableWorkspaceNode customers = TableWorkspaceNode.create(ALIAS, "app", "customers", GraphActor.extractor);
        customers.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(customers.getId(), customers);

        RelationWorkspaceEdge relation = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, "app", "orders", "customer_id"),
                GraphIds.columnId(ALIAS, "app", "customers", "id"), GraphActor.human);
        relation.setConfidence(0.95);
        relation.setVerified(true);
        workspace.getRelations().add(relation);

        // includeIsolated=true：两张表无论有没有边都在结果里，这样边数变化不会被
        // "孤立表被过滤掉"的逻辑一起带偏，assertion 只对边数本身负责。
        GraphViewDto before = new GraphViewService(workspace).buildView(
                null, null, 1, null, true, 500, 1000);
        assertEquals(1, before.getEdges().size(), "关系还是候选/正式状态时，画布应该画出这条边");
        assertEquals(1, before.getStats().getTotalEdges());
        assertEquals(1, nodeRelationCount(before, orders.getId()));

        relation.setStatus(GraphStatus.ignored);

        GraphViewDto after = new GraphViewService(workspace).buildView(
                null, null, 1, null, true, 500, 1000);
        assertEquals(0, after.getEdges().size(), "人拒绝过的关系不能出现在画布边列表里");
        assertEquals(0, after.getStats().getTotalEdges());
        assertEquals(0, nodeRelationCount(after, orders.getId()), "关系数也不能把被拒绝的边算进去");
        assertEquals(2, after.getNodes().size(), "节点本身不受影响，两张表都还在");
    }

    private int nodeRelationCount(GraphViewDto view, String tableId) {
        return view.getNodes().stream()
                .filter(node -> tableId.equals(node.getId()))
                .findFirst().orElseThrow().getRelationCount();
    }
}
