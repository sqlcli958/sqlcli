package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖 dev-checklist P1 第 1 条：复合外键不能被 getImportedKeys 逐行拆成互不相关的边。
 *
 * MySQL 元数据下 PKTABLE_SCHEM 恒为 null、PKTABLE_CAT 存 schema 名（JDBC 对 MySQL 的既定映射），
 * 与 WorkspaceMetadataExtractor.resolveSchema 的取值顺序一致，测试按这个真实形状造数据。
 */
class WorkspaceMetadataExtractorForeignKeyTest {

    private WorkspaceMetadataExtractor newExtractor(DatabaseMetaData metaData) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("trade");
        when(metaData.getURL()).thenReturn("jdbc:mysql://localhost/trade");
        return new WorkspaceMetadataExtractor(connection, "unit");
    }

    private TableWorkspaceNode childTable() {
        TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        table.getColumns().add(nullableColumn("tenant_id", true));
        table.getColumns().add(nullableColumn("order_no", false));
        return table;
    }

    private ColumnWorkspaceNode nullableColumn(String name, boolean nullable) {
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create(name);
        column.setNullable(nullable);
        return column;
    }

    private GraphWorkspace targetWorkspaceWithParent() {
        GraphWorkspace workspace = GraphWorkspace.create("unit", "mysql");
        TableWorkspaceNode parent = TableWorkspaceNode.create("unit", "trade", "order_master", GraphActor.extractor);
        parent.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        parent.getColumns().add(ColumnWorkspaceNode.create("order_no"));
        workspace.getTables().put(parent.getId(), parent);
        return workspace;
    }

    /** 两行 ResultSet 模拟一个两列复合外键，FK_NAME 相同、KEY_SEQ 从 1 递增。 */
    private ResultSet twoColumnFkResultSet(String fkName) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("tenant_id", "order_no");
        when(rs.getString("PKTABLE_SCHEM")).thenReturn(null, null);
        when(rs.getString("PKTABLE_CAT")).thenReturn("trade", "trade");
        when(rs.getString("PKTABLE_NAME")).thenReturn("order_master", "order_master");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("tenant_id", "order_no");
        when(rs.getString("FK_NAME")).thenReturn(fkName, fkName);
        when(rs.getInt("KEY_SEQ")).thenReturn(1, 2);
        return rs;
    }

    @Test
    @DisplayName("两列复合外键还原成一组，joinExpression 含全部列对")
    void groupsCompositeForeignKeyAndBuildsFullJoinExpression() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        WorkspaceMetadataExtractor extractor = newExtractor(metaData);
        TableWorkspaceNode table = childTable();
        // 先求值再打桩：直接写成 thenReturn(twoColumnFkResultSet(...)) 会在外层 when() 的
        // 打桩尚未结束时进入 helper 再调 when()，Mockito 抛 UnfinishedStubbing。
        ResultSet importedKeys = twoColumnFkResultSet("fk_orders_order_master");
        when(metaData.getImportedKeys("trade", null, "orders")).thenReturn(importedKeys);

        GraphWorkspace target = targetWorkspaceWithParent();
        extractor.extractForeignKeysForTable(table, target);

        List<RelationWorkspaceEdge> fks = target.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).toList();
        assertEquals(2, fks.size());

        String expectedJoin = "trade.orders.tenant_id = trade.order_master.tenant_id"
                + " AND trade.orders.order_no = trade.order_master.order_no";
        for (RelationWorkspaceEdge edge : fks) {
            assertEquals(expectedJoin, edge.getJoinExpression(), "组内每条边都要携带完整的 AND 条件");
            assertEquals("fk_orders_order_master", edge.getFkGroup());
        }
        // KEY_SEQ 分别落在各自的边上
        assertTrue(fks.stream().anyMatch(e -> e.getFkKeySeq() != null && e.getFkKeySeq() == 1));
        assertTrue(fks.stream().anyMatch(e -> e.getFkKeySeq() != null && e.getFkKeySeq() == 2));
    }

    @Test
    @DisplayName("单列外键的 joinExpression 与改动前逐字相同")
    void singleColumnJoinExpressionUnchanged() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        WorkspaceMetadataExtractor extractor = newExtractor(metaData);
        TableWorkspaceNode table = childTable();

        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("order_no");
        when(rs.getString("PKTABLE_SCHEM")).thenReturn((String) null);
        when(rs.getString("PKTABLE_CAT")).thenReturn("trade");
        when(rs.getString("PKTABLE_NAME")).thenReturn("order_master");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("order_no");
        when(rs.getString("FK_NAME")).thenReturn("fk_orders_order_no");
        when(rs.getInt("KEY_SEQ")).thenReturn(1);
        when(metaData.getImportedKeys("trade", null, "orders")).thenReturn(rs);

        GraphWorkspace target = targetWorkspaceWithParent();
        extractor.extractForeignKeysForTable(table, target);

        RelationWorkspaceEdge edge = target.getRelations().get(0);
        assertEquals("trade.orders.order_no = trade.order_master.order_no", edge.getJoinExpression());
    }

    @Test
    @DisplayName("FK_NAME 为 null 时仍按 KEY_SEQ 正确分组，推导稳定标识")
    void groupsWhenFkNameMissing() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        WorkspaceMetadataExtractor extractor = newExtractor(metaData);
        TableWorkspaceNode table = childTable();
        // 先求值再打桩：直接写成 thenReturn(twoColumnFkResultSet(...)) 会在外层 when() 的
        // 打桩尚未结束时进入 helper 再调 when()，Mockito 抛 UnfinishedStubbing。
        ResultSet importedKeys = twoColumnFkResultSet(null);
        when(metaData.getImportedKeys("trade", null, "orders")).thenReturn(importedKeys);

        GraphWorkspace target = targetWorkspaceWithParent();
        extractor.extractForeignKeysForTable(table, target);

        List<RelationWorkspaceEdge> fks = target.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).toList();
        assertEquals(2, fks.size());
        String group = fks.get(0).getFkGroup();
        assertNotNull(group);
        assertEquals(group, fks.get(1).getFkGroup(), "没有 FK_NAME 也要落进同一组");
        // 推导标识里要看得出目标表 + 全部列对，不是随手生成的占位符
        assertTrue(group.contains("order_master"));
        assertTrue(group.contains("tenant_id->tenant_id"));
        assertTrue(group.contains("order_no->order_no"));
    }

    @Test
    @DisplayName("同子表对同一父表的两个复合外键，KEY_SEQ 交错也不串味")
    void groupsTwoCompositeForeignKeysToSameParentInterleavedByKeySeq() throws Exception {
        // JDBC 对 getImportedKeys 的排序约定是 PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME,
        // KEY_SEQ——排序键里没有 FK_NAME。单据表对同一张主数据表的两个复合外键
        // （from_warehouse / to_warehouse 都指向 warehouse(tenant_id, code)）会按
        // KEY_SEQ 交错返回：(1,fk_from) (1,fk_to) (2,fk_from) (2,fk_to)。
        // 只按「KEY_SEQ 重置为 1」分组会把这四行错分成两组、互相串列，必须靠 FK_NAME 分组。
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        WorkspaceMetadataExtractor extractor = newExtractor(metaData);

        TableWorkspaceNode table = TableWorkspaceNode.create("unit", "trade", "orders", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("from_tenant_id"));
        table.getColumns().add(ColumnWorkspaceNode.create("to_tenant_id"));
        table.getColumns().add(ColumnWorkspaceNode.create("from_code"));
        table.getColumns().add(ColumnWorkspaceNode.create("to_code"));

        GraphWorkspace target = GraphWorkspace.create("unit", "mysql");
        TableWorkspaceNode warehouse = TableWorkspaceNode.create("unit", "trade", "warehouse", GraphActor.extractor);
        warehouse.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        warehouse.getColumns().add(ColumnWorkspaceNode.create("code"));
        target.getTables().put(warehouse.getId(), warehouse);

        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true, true, false);
        when(rs.getString("FKCOLUMN_NAME")).thenReturn(
                "from_tenant_id", "to_tenant_id", "from_code", "to_code");
        when(rs.getString("PKTABLE_SCHEM")).thenReturn((String) null, null, null, null);
        when(rs.getString("PKTABLE_CAT")).thenReturn("trade", "trade", "trade", "trade");
        when(rs.getString("PKTABLE_NAME")).thenReturn("warehouse", "warehouse", "warehouse", "warehouse");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("tenant_id", "tenant_id", "code", "code");
        when(rs.getString("FK_NAME")).thenReturn(
                "fk_from_warehouse", "fk_to_warehouse", "fk_from_warehouse", "fk_to_warehouse");
        when(rs.getInt("KEY_SEQ")).thenReturn(1, 1, 2, 2);
        when(metaData.getImportedKeys("trade", null, "orders")).thenReturn(rs);

        extractor.extractForeignKeysForTable(table, target);

        List<RelationWorkspaceEdge> fks = target.getRelations().stream()
                .filter(r -> r.getType() == RelationType.foreign_key).toList();
        assertEquals(4, fks.size());

        List<RelationWorkspaceEdge> fromGroup = fks.stream()
                .filter(e -> "fk_from_warehouse".equals(e.getFkGroup())).toList();
        List<RelationWorkspaceEdge> toGroup = fks.stream()
                .filter(e -> "fk_to_warehouse".equals(e.getFkGroup())).toList();
        assertEquals(2, fromGroup.size(), "fk_from_warehouse 组不能缺列也不能混进 fk_to_warehouse 的列");
        assertEquals(2, toGroup.size(), "fk_to_warehouse 组同理");

        String expectedFrom = "trade.orders.from_tenant_id = trade.warehouse.tenant_id"
                + " AND trade.orders.from_code = trade.warehouse.code";
        String expectedTo = "trade.orders.to_tenant_id = trade.warehouse.tenant_id"
                + " AND trade.orders.to_code = trade.warehouse.code";
        for (RelationWorkspaceEdge edge : fromGroup) {
            assertEquals(expectedFrom, edge.getJoinExpression(), "不能混进另一个约束的列");
        }
        for (RelationWorkspaceEdge edge : toGroup) {
            assertEquals(expectedTo, edge.getJoinExpression());
        }
    }

    @Test
    @DisplayName("外键列 nullable 分别得到正确的可选性初值，并标注为推断值")
    void setsOptionalityFromNullable() throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        WorkspaceMetadataExtractor extractor = newExtractor(metaData);
        TableWorkspaceNode table = childTable(); // tenant_id nullable=true, order_no nullable=false
        // 先求值再打桩：直接写成 thenReturn(twoColumnFkResultSet(...)) 会在外层 when() 的
        // 打桩尚未结束时进入 helper 再调 when()，Mockito 抛 UnfinishedStubbing。
        ResultSet importedKeys = twoColumnFkResultSet("fk_orders_order_master");
        when(metaData.getImportedKeys("trade", null, "orders")).thenReturn(importedKeys);

        GraphWorkspace target = targetWorkspaceWithParent();
        extractor.extractForeignKeysForTable(table, target);

        RelationWorkspaceEdge tenantEdge = target.getRelations().stream()
                .filter(r -> r.getFrom().endsWith(".tenant_id")).findFirst().orElseThrow();
        RelationWorkspaceEdge orderNoEdge = target.getRelations().stream()
                .filter(r -> r.getFrom().endsWith(".order_no")).findFirst().orElseThrow();

        assertEquals(Boolean.TRUE, tenantEdge.getFromOptional(), "外键列 nullable=true -> from 端可选");
        assertEquals(Boolean.FALSE, orderNoEdge.getFromOptional(), "外键列 nullable=false -> from 端不可选");
        assertTrue(tenantEdge.isOptionalityInferred());
        assertTrue(orderNoEdge.isOptionalityInferred());
        // FK 边本身仍然是既成事实，不因为 optionality 是推断值就跟着降级
        assertTrue(tenantEdge.getVerified());
        assertEquals(GraphStatus.verified, tenantEdge.getStatus());
    }
}
