package com.sqlcli.graph.ui.api;

import com.sqlcli.graph.workspace.ColumnValueHints;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.SemanticType;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射规则的钉子：只在「单一表 + 不带别名的裸列」时标注，其余一律空手而归——
 * 标错业务含义比不标糟糕得多。
 */
class WorkbenchColumnMetaTest {

    private GraphWorkspace workspace() {
        GraphWorkspace ws = GraphWorkspace.create("demo", "mysql");

        TableWorkspaceNode orders = TableWorkspaceNode.create("demo", "shop", "orders", GraphActor.system);
        ColumnWorkspaceNode status = ColumnWorkspaceNode.create("status");
        status.setBusinessName("订单状态");
        status.setSemanticType(SemanticType.status);
        ColumnValueHints hints = new ColumnValueHints();
        hints.getEnumValues().add("0=待付款");
        hints.getEnumValues().add("1=已付款");
        status.setValueHints(hints);
        orders.getColumns().add(status);
        ColumnWorkspaceNode phone = ColumnWorkspaceNode.create("phone");
        phone.setBusinessName("买家手机号");
        orders.getColumns().add(phone);
        orders.getColumns().add(ColumnWorkspaceNode.create("id")); // 没有任何业务信息
        ws.getTables().put(orders.getId(), orders);

        // 另一个 schema 里的同名表：裸表名会歧义
        TableWorkspaceNode archived = TableWorkspaceNode.create("demo", "archive", "orders", GraphActor.system);
        archived.getColumns().add(ColumnWorkspaceNode.create("status"));
        ws.getTables().put(archived.getId(), archived);
        return ws;
    }

    @Test
    void selectStarOnQualifiedTableMatchesIgnoringCase() {
        Map<String, Map<String, Object>> meta = WorkbenchColumnMeta.resolve(
                "SELECT * FROM shop.orders WHERE id = 1", workspace(), List.of("STATUS", "phone", "id"));
        assertEquals(2, meta.size());
        assertEquals("订单状态", meta.get("STATUS").get("businessName"));
        assertEquals("status", meta.get("STATUS").get("semanticType"));
        assertEquals(List.of("0=待付款", "1=已付款"), meta.get("STATUS").get("enumValues"));
        assertEquals("买家手机号", meta.get("phone").get("businessName"));
        // 图谱里有 id 列但它没有任何业务信息，不出条目
        assertFalse(meta.containsKey("id"));
    }

    @Test
    void aliasedAndExpressionColumnsAreNeverAnnotated() {
        // phone AS status：结果列名 status 撞上图谱另一个列——绝不能拿错含义
        assertTrue(WorkbenchColumnMeta.resolve(
                "SELECT phone AS status FROM shop.orders", workspace(), List.of("status")).isEmpty());
        // 显式裸列可以标，表达式列不标
        Map<String, Map<String, Object>> meta = WorkbenchColumnMeta.resolve(
                "SELECT status, UPPER(phone) AS p FROM shop.orders", workspace(), List.of("status", "p"));
        assertEquals(1, meta.size());
        assertEquals("订单状态", meta.get("status").get("businessName"));
    }

    @Test
    void joinSubqueryAndNonSelectYieldNothing() {
        GraphWorkspace ws = workspace();
        List<String> cols = List.of("status");
        assertTrue(WorkbenchColumnMeta.resolve(
                "SELECT o.status FROM shop.orders o JOIN shop.users u ON u.id = o.uid", ws, cols).isEmpty());
        assertTrue(WorkbenchColumnMeta.resolve(
                "SELECT status FROM (SELECT * FROM shop.orders) t", ws, cols).isEmpty());
        assertTrue(WorkbenchColumnMeta.resolve(
                "UPDATE shop.orders SET status = 1 WHERE id = 2", ws, cols).isEmpty());
        assertTrue(WorkbenchColumnMeta.resolve("not sql at all", ws, cols).isEmpty());
    }

    @Test
    void bareTableNameAmbiguousAcrossSchemasIsNotGuessed() {
        // shop.orders 和 archive.orders 同名：裸表名放弃，带 schema 前缀才标
        assertTrue(WorkbenchColumnMeta.resolve(
                "SELECT * FROM orders", workspace(), List.of("status")).isEmpty());
        assertFalse(WorkbenchColumnMeta.resolve(
                "SELECT * FROM shop.orders", workspace(), List.of("status")).isEmpty());
    }
}
