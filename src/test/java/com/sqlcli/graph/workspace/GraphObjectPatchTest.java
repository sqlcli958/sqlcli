package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图谱审批把「改的是哪个对象」存进 db、批准时再写回去，这两步全压在
 * {@link GraphObjectPatch} 上——定位错一类对象，那类变更就永远批不下去，
 * 或者更糟：批下去写到了别的地方。所以每一类都要有来回各走一遍的验证。
 */
class GraphObjectPatchTest {

    private static final String ALIAS = "demo";

    private GraphWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        workspace.getTables().put(orders.getId(), orders);
    }

    @Test
    void unknownPrefixIsNotSupported() {
        // manifest id 走的就是这条路：整份快照导入、建索引、跑校验都不该进审批队列
        assertNull(GraphObjectPatch.kindOf("workspace-manifest-123"));
        assertNull(GraphObjectPatch.kindOf(null));
        assertNull(GraphObjectPatch.kindOf("nocolon"));
        assertTrue(GraphObjectPatch.supports(GraphIds.tableId(ALIAS, "app", "orders")));
    }

    @Test
    void tableRoundTrip() {
        String id = GraphIds.tableId(ALIAS, "app", "orders");
        TableWorkspaceNode table = (TableWorkspaceNode) GraphObjectPatch.read(workspace, id);
        assertNotNull(table);

        table.setDescription("订单主表");
        GraphObjectPatch.write(workspace, id, table);
        assertEquals("订单主表", workspace.getTables().get(id).getDescription());

        GraphObjectPatch.write(workspace, id, null);
        assertNull(GraphObjectPatch.read(workspace, id));
    }

    /** 列住在表里，写回要先把表找出来——这是六类里唯一不能靠一个 Map.put 搞定的。 */
    @Test
    void columnRoundTripFindsItsTable() {
        String id = GraphIds.columnId(ALIAS, "app", "orders", "user_id");
        ColumnWorkspaceNode column = (ColumnWorkspaceNode) GraphObjectPatch.read(workspace, id);
        assertNotNull(column);

        column.setDescription("下单用户 ID");
        GraphObjectPatch.write(workspace, id, column);
        TableWorkspaceNode table = workspace.getTables().get(GraphIds.tableId(ALIAS, "app", "orders"));
        assertEquals(1, table.getColumns().size(), "写回是替换，不是追加一份重复的");
        assertEquals("下单用户 ID", table.findColumn("user_id").getDescription());

        GraphObjectPatch.write(workspace, id, null);
        assertNull(table.findColumn("user_id"));
    }

    @Test
    void columnWriteFailsLoudlyWhenItsTableIsGone() {
        String id = GraphIds.columnId(ALIAS, "app", "gone", "user_id");
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create("user_id");
        // 静默 no-op 的话，批准会报成功而图谱纹丝不动——最难查的那种
        assertThrows(IllegalArgumentException.class,
                () -> GraphObjectPatch.write(workspace, id, column));
    }

    @Test
    void relationRoundTripReplacesInPlace() {
        String from = GraphIds.columnId(ALIAS, "app", "orders", "user_id");
        String to = GraphIds.columnId(ALIAS, "app", "users", "id");
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(
                ALIAS, RelationType.join_observed, from, to, GraphActor.agent);
        String id = edge.getId();

        assertNull(GraphObjectPatch.read(workspace, id), "写之前不存在——新增变更的 before");
        GraphObjectPatch.write(workspace, id, edge);
        assertEquals(1, workspace.getRelations().size());

        edge.setConfidence(0.95);
        GraphObjectPatch.write(workspace, id, edge);
        assertEquals(1, workspace.getRelations().size(), "同 id 是替换，不是再加一条");
        assertEquals(0.95, workspace.getRelations().get(0).getConfidence());

        GraphObjectPatch.write(workspace, id, null);
        assertTrue(workspace.getRelations().isEmpty());
    }

    @Test
    void termAndMetricAndLineageUseTheirOwnMaps() {
        String termId = "term:" + ALIAS + ":GMV";
        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "GMV", GraphActor.agent);
        GraphObjectPatch.write(workspace, termId, term);
        assertEquals(1, workspace.getTerms().size());
        assertNotNull(GraphObjectPatch.read(workspace, termId));

        String metricId = GraphIds.metricId(ALIAS, "gmv");
        MetricRecord metric = new MetricRecord();
        metric.setId(metricId);
        GraphObjectPatch.write(workspace, metricId, metric);
        assertEquals(1, workspace.getMetrics().size());
        assertNotNull(GraphObjectPatch.read(workspace, metricId));

        String lineageId = "lineage:" + ALIAS + ":x:abcd";
        LineageRecord lineage = new LineageRecord();
        lineage.setId(lineageId);
        GraphObjectPatch.write(workspace, lineageId, lineage);
        assertEquals(1, workspace.getLineage().size());
        assertNotNull(GraphObjectPatch.read(workspace, lineageId));

        // 各回各家：写 term 不该碰 metric / lineage
        assertEquals(1, workspace.getTerms().size());
    }
}
