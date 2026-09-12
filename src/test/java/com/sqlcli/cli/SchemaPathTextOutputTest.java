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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code schema path} 纯文本分支的 join 提示。
 *
 * <p>另一个并行改动给 {@code WorkspacePathFinder.PathSegment} 加了 joinExpression /
 * fromOptional / toOptional 三个字段（复合外键分组的产物），{@code --json} 分支整体序列化
 * PathSegment 自动带出去了，但纯文本分支原来只打印 fromTable.fromColumn -> toTable.toColumn
 * [type]，三个新字段一个都没露出来——这里补上，两条用例覆盖单列外键和复合外键。
 */
class SchemaPathTextOutputTest {

    @TempDir
    Path temp;

    @Test
    void singleColumnForeignKeyPrintsInnerJoinWithFullCondition() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        TableWorkspaceNode users = TableWorkspaceNode.create("commands", "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create("commands", RelationType.foreign_key,
                "column:commands:app.orders.user_id", "column:commands:app.users.id", GraphActor.extractor);
        edge.setJoinExpression("app.orders.user_id = app.users.id");
        // 外键列非空：from 端不可选，安全用 INNER
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, false);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, true);
        workspace.getRelations().add(edge);
        store.save(workspace);

        String stdout = runPath(store, "app.orders", "app.users");

        assertTrue(stdout.contains("INNER JOIN"), stdout);
        assertTrue(stdout.contains("ON app.orders.user_id = app.users.id"), stdout);
        // 结论必须带"推断"标记，不能让人误以为是数据库声明的确认值
        assertTrue(stdout.contains("推断"), stdout);
        // 不该出现 LEFT，避免结论和 fromOptional=false 对不上
        assertTrue(!stdout.contains("LEFT JOIN"), stdout);
    }

    @Test
    void compositeForeignKeyPrintsLeftJoinWithFullCompoundCondition() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create("commands", "trade", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("order_no"));
        TableWorkspaceNode master = TableWorkspaceNode.create("commands", "trade", "order_master", GraphActor.extractor);
        master.getColumns().add(ColumnWorkspaceNode.create("tenant_id"));
        master.getColumns().add(ColumnWorkspaceNode.create("order_no"));
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(master.getId(), master);

        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create("commands", RelationType.foreign_key,
                "column:commands:trade.orders.tenant_id", "column:commands:trade.order_master.tenant_id",
                GraphActor.extractor);
        String compound = "trade.orders.tenant_id = trade.order_master.tenant_id"
                + " AND trade.orders.order_no = trade.order_master.order_no";
        edge.setJoinExpression(compound);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FK_GROUP, "fk_orders_order_master");
        // 外键列可空：from 端可选，INNER 会静默丢行，必须给 LEFT 结论
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, true);
        workspace.getRelations().add(edge);
        store.save(workspace);

        String stdout = runPath(store, "trade.orders", "trade.order_master");

        assertTrue(stdout.contains("LEFT JOIN"), stdout);
        assertTrue(stdout.contains("推断"), stdout);
        // 完整可用是硬要求：两组列对都要在，不能只带第一组就当打印完了
        assertTrue(stdout.contains("ON " + compound), stdout);
    }

    private String runPath(GraphWorkspaceStore store, String table1, String table2) throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias("commands");
        command.setAction("path");
        command.setTableName(table1);
        command.setTableName2(table2);

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
