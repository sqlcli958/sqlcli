package com.sqlcli.cli;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1：{@code describe} 输出里给 agent 判断「这张表能不能直接用」的三样东西。
 *
 * <p>图谱是<b>按需生长</b>的（技能明确禁止全仓扫描），所以任何时刻都是部分完整的。
 * agent 面对一张没有描述的表分不清「这张表确实简单」和「没人干过这块」，
 * 而这两种情况下该做的事完全相反——一个直接用，一个必须先读代码。
 *
 * <p>三条用例各钉一个在真库上踩过的坑，见每个方法的注释。
 */
class SchemaDescribeCompletenessTest {

    @TempDir
    Path temp;

    /**
     * 整理痕迹看<b>内容</b>，不看 {@code updatedBy}。
     *
     * <p>真库反例：{@code erp_prop_report} 被 agent 写满了描述、值域和 grain，
     * 而它的 {@code updatedBy} 是 {@code system}——回写行数那次记账把 actor 盖掉了。
     * 拿 actor 当判据会把做透的表报成「从未有人补充」，正好把这条提示的唯一用处反过来。
     */
    @Test
    void curatedIsDerivedFromContentNotFromUpdatedBy() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = table("app", "orders");
        orders.setDescription("订单主表");
        ColumnWorkspaceNode status = ColumnWorkspaceNode.create("status");
        status.setDescription("订单状态");
        orders.getColumns().add(status);
        // 记账动作把 actor 盖成 system——这正是真库里的样子
        orders.setUpdatedBy(GraphActor.system);
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        String stdout = describe(store, "app.orders", null);

        assertTrue(stdout.contains("有人整理过"), stdout);
        assertFalse(stdout.contains("从未有人补充"), stdout);
        assertTrue(stdout.contains("描述 1/1"), stdout);
    }

    /**
     * 一张只有库注释的表必须明说「从未有人补充」，并给出下一步动作。
     *
     * <p>不说的话 agent 只能在盲信和全不信之间二选一，而<b>盲信不报错</b>。
     */
    @Test
    void untouchedTableSaysNobodyCuratedItAndTellsAgentToReadCode() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode logs = table("app", "logs");
        logs.setComment("导出记录表");
        ColumnWorkspaceNode id = ColumnWorkspaceNode.create("id");
        id.setComment("主键");
        logs.getColumns().add(id);
        workspace.getTables().put(logs.getId(), logs);
        store.save(workspace);

        String stdout = describe(store, "app.logs", null);

        assertTrue(stdout.contains("从未有人补充"), stdout);
        assertTrue(stdout.contains("先读代码"), stdout);
    }

    /**
     * 基数缺失时按主键推断，并且两条一对多要触发扇形陷阱提示。
     *
     * <p>真库反例：{@code erp_prop_report} 的 14 条关系基数<b>全是 unknown</b>——
     * agent 写 {@code add-relation} 时基本不填。不推断的话这条提示永远不触发，
     * 整条消费路径是死的。推断只读 DDL 已声明的约束，不猜业务。
     */
    @Test
    void unknownCardinalityIsInferredFromPrimaryKeyAndTwoFanInsWarn() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");

        TableWorkspaceNode orders = table("app", "orders");
        ColumnWorkspaceNode orderId = ColumnWorkspaceNode.create("id");
        orderId.setPrimaryKey(true);
        orders.getColumns().add(orderId);
        TableWorkspaceNode items = table("app", "order_items");
        items.getColumns().add(ColumnWorkspaceNode.create("order_id"));
        TableWorkspaceNode payments = table("app", "payments");
        payments.getColumns().add(ColumnWorkspaceNode.create("order_id"));
        for (TableWorkspaceNode node : java.util.List.of(orders, items, payments)) {
            workspace.getTables().put(node.getId(), node);
        }
        // 两条指向 orders 主键的边，基数都不填——真库里就是这个状态
        workspace.getRelations().add(RelationWorkspaceEdge.create("commands", RelationType.join_observed,
                "column:commands:app.order_items.order_id", "column:commands:app.orders.id",
                GraphActor.agent));
        workspace.getRelations().add(RelationWorkspaceEdge.create("commands", RelationType.join_observed,
                "column:commands:app.payments.order_id", "column:commands:app.orders.id",
                GraphActor.agent));
        store.save(workspace);

        String stdout = describe(store, "app.orders", null);

        assertTrue(stdout.contains("N:1(推断)"), stdout);
        // 推断出来的不能再算进「基数未知」，否则这个数和上面的列表自相矛盾
        assertFalse(stdout.contains("基数未知 2"), stdout);
        assertTrue(stdout.contains("扇形陷阱"), stdout);
    }

    /** {@code --column} 展开单列，且不再打印整表——上下文预算就是这么省下来的。 */
    @Test
    void singleColumnViewExpandsDetailAndSkipsTheFullTableDump() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = table("app", "orders");
        ColumnWorkspaceNode status = ColumnWorkspaceNode.create("status");
        status.setDescription("订单状态");
        status.setConfidence(0.8);
        orders.getColumns().add(status);
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        String stdout = describe(store, "app.orders", "status");

        assertTrue(stdout.contains("Column: app.orders.status"), stdout);
        assertTrue(stdout.contains("置信度: 0.8"), stdout);
        assertTrue(stdout.contains("人工确认: 否"), stdout);
        // 另一列不该出现——出现了就说明还是把整表打了一遍，预算没省下来
        assertFalse(stdout.contains("buyer_id"), stdout);
    }

    private static TableWorkspaceNode table(String schema, String name) {
        return TableWorkspaceNode.create("commands", schema, name, GraphActor.extractor);
    }

    private String describe(GraphWorkspaceStore store, String table, String column) throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias("commands");
        command.setAction("describe");
        command.setTableName(table);
        command.setEditColumn(column);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertEquals(0, command.executeCommand());
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
