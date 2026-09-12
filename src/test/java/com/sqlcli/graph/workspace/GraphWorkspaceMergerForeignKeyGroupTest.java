package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 dev-checklist P1 第 1 条里点名的合并回归点：复合外键的多列边共享 fkGroup，
 * 增量合并（mergeBatch 带 scannedFkTableIds）不能因为整表一刀切删除再加回，
 * 把 incoming 这一批没提到的同组边当成「已删除」误删掉。
 */
class GraphWorkspaceMergerForeignKeyGroupTest {

    private GraphWorkspaceMerger merger;
    private static final String TENANT_COL = "column:unit:trade.orders.tenant_id";
    private static final String ORDER_NO_COL = "column:unit:trade.orders.order_no";
    private static final String PARENT_TENANT_COL = "column:unit:trade.order_master.tenant_id";
    private static final String PARENT_ORDER_NO_COL = "column:unit:trade.order_master.order_no";
    private static final String OWNING_TABLE_ID = "table:unit:trade.orders";
    private static final String GROUP = "fk_orders_order_master";

    @BeforeEach
    void setUp() {
        merger = new GraphWorkspaceMerger();
    }

    private RelationWorkspaceEdge groupEdge(String from, String to, int keySeq) {
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create("unit", RelationType.foreign_key, from, to,
                GraphActor.extractor);
        edge.setJoinExpression("trade.orders.tenant_id = trade.order_master.tenant_id"
                + " AND trade.orders.order_no = trade.order_master.order_no");
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_GROUP, GROUP);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_KEY_SEQ, keySeq);
        edge.setVerified(true);
        return edge;
    }

    private GraphWorkspace workspace() {
        return GraphWorkspace.create("unit", "mysql");
    }

    @Test
    @DisplayName("完整两条边的一批合并后同组两条边都在")
    void fullGroupMergesIntact() {
        GraphWorkspace existing = workspace();
        GraphWorkspace incoming = workspace();
        incoming.getRelations().add(groupEdge(TENANT_COL, PARENT_TENANT_COL, 1));
        incoming.getRelations().add(groupEdge(ORDER_NO_COL, PARENT_ORDER_NO_COL, 2));

        GraphWorkspace result = merger.mergeBatch(incoming, existing, Set.of(OWNING_TABLE_ID));

        List<RelationWorkspaceEdge> fks = result.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).toList();
        assertEquals(2, fks.size());
    }

    @Test
    @DisplayName("第二批只带来同组一条边，另一条不丢失")
    void secondBatchWithOnlyOneGroupMemberDoesNotDropSibling() {
        GraphWorkspace existing = workspace();
        // 第一批：完整两列都已入库
        existing.getRelations().add(groupEdge(TENANT_COL, PARENT_TENANT_COL, 1));
        existing.getRelations().add(groupEdge(ORDER_NO_COL, PARENT_ORDER_NO_COL, 2));

        // 第二批：incoming 只带回了组里的一条边（正常的 extractForeignKeysForTable 不会
        // 产出这种输入，但合并器要防住这种半组数据，不然复合外键被腰斩）
        GraphWorkspace incoming = workspace();
        incoming.getRelations().add(groupEdge(TENANT_COL, PARENT_TENANT_COL, 1));

        GraphWorkspace result = merger.mergeBatch(incoming, existing, Set.of(OWNING_TABLE_ID));

        List<RelationWorkspaceEdge> fks = result.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).toList();
        assertEquals(2, fks.size(), "同组另一条边不应该被整表清理逻辑连带删掉");
        assertTrue(fks.stream().anyMatch(e -> e.getFrom().equals(ORDER_NO_COL)));
    }

    @Test
    @DisplayName("整组在 incoming 中完全消失时才整组清理（约束确实被删除）")
    void wholeGroupRemovedWhenAbsentFromIncoming() {
        GraphWorkspace existing = workspace();
        existing.getRelations().add(groupEdge(TENANT_COL, PARENT_TENANT_COL, 1));
        existing.getRelations().add(groupEdge(ORDER_NO_COL, PARENT_ORDER_NO_COL, 2));

        // incoming 里这张表没有任何 FK 边了（约束在 DB 里被删除）
        GraphWorkspace incoming = workspace();

        GraphWorkspace result = merger.mergeBatch(incoming, existing, Set.of(OWNING_TABLE_ID));

        long fkCount = result.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).count();
        assertEquals(0, fkCount, "整组都不在 incoming 里时要清理掉，这是探知约束被删除的唯一信号");
    }
}
