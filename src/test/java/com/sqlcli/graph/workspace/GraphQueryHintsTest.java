package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQueryHintsTest {

    private GraphWorkspace workspace() {
        GraphWorkspace workspace = GraphWorkspace.create("demo", "mysql");

        TableWorkspaceNode orders = TableWorkspaceNode.create("demo", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("id"));
        orders.getColumns().add(ColumnWorkspaceNode.create("user_id"));
        ColumnWorkspaceNode status = ColumnWorkspaceNode.create("status");
        ColumnValueHints hints = new ColumnValueHints();
        hints.getEnumValues().add("0=待付款");
        hints.getEnumValues().add("1=已付款");
        status.setValueHints(hints);
        orders.getColumns().add(status);
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create("demo", "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);
        return workspace;
    }

    @Test
    void recognizesUnknownIdentifierMessages() {
        assertTrue(GraphQueryHints.looksLikeUnknownIdentifier("Unknown column 'usr_id' in 'field list'"));
        assertTrue(GraphQueryHints.looksLikeUnknownIdentifier("ERROR: column \"usr_id\" does not exist"));
        assertTrue(GraphQueryHints.looksLikeUnknownIdentifier("ORA-00904: \"USR_ID\": invalid identifier"));
        assertTrue(GraphQueryHints.looksLikeUnknownIdentifier("Table 'app.ordr' doesn't exist"));
        assertTrue(GraphQueryHints.looksLikeUnknownIdentifier("ERROR: relation \"ordr\" does not exist"));
        assertTrue(GraphQueryHints.looksLikeUnknownIdentifier("ORA-00942: table or view does not exist"));
        assertFalse(GraphQueryHints.looksLikeUnknownIdentifier("Communications link failure"));
        assertFalse(GraphQueryHints.looksLikeUnknownIdentifier(null));
    }

    @Test
    void suggestsColumnForMysqlUnknownColumn() {
        String hint = GraphQueryHints.suggestForError(
                "Unknown column 'usr_id' in 'field list'", "SELECT usr_id FROM orders", workspace());
        assertTrue(hint.contains("usr_id"), hint);
        assertTrue(hint.contains("app.orders.user_id"), hint);
    }

    @Test
    void suggestsColumnForOracleQualifiedIdentifier() {
        String hint = GraphQueryHints.suggestForError(
                "ORA-00904: \"T\".\"USER_IDX\": invalid identifier", "SELECT t.user_idx FROM orders t", workspace());
        assertTrue(hint.contains("app.orders.user_id"), hint);
    }

    @Test
    void suggestsTableForPostgresMissingRelation() {
        String hint = GraphQueryHints.suggestForError(
                "ERROR: relation \"ordr\" does not exist", "SELECT * FROM ordr", workspace());
        assertTrue(hint.contains("app.orders"), hint);
    }

    @Test
    void suggestsTableForOra942ByParsingSql() {
        String hint = GraphQueryHints.suggestForError(
                "ORA-00942: table or view does not exist", "SELECT * FROM oders WHERE id = 1", workspace());
        assertTrue(hint.contains("app.orders"), hint);
    }

    @Test
    void returnsNullWhenNothingSimilar() {
        assertNull(GraphQueryHints.suggestForError(
                "Unknown column 'zzzzzz' in 'field list'", "SELECT zzzzzz FROM orders", workspace()));
        assertNull(GraphQueryHints.suggestForError("Communications link failure", "SELECT 1", workspace()));
    }

    @Test
    void emptyResultHintFlagsValueOutsideKnownDomain() {
        String hint = GraphQueryHints.emptyResultHint(
                "SELECT * FROM orders WHERE status = '已付款'", workspace());
        assertTrue(hint.contains("app.orders.status"), hint);
        assertTrue(hint.contains("0=待付款"), hint);
    }

    @Test
    void emptyResultHintAcceptsValuesInsideDomain() {
        // 值域项是 0=待付款，查询写 status = '0' 是合法的，不该提示
        assertNull(GraphQueryHints.emptyResultHint("SELECT * FROM orders WHERE status = '0'", workspace()));
        assertNull(GraphQueryHints.emptyResultHint("SELECT * FROM orders WHERE status = 1", workspace()));
        // 没配值域的列不参与
        assertNull(GraphQueryHints.emptyResultHint("SELECT * FROM orders WHERE user_id = 42", workspace()));
    }

    @Test
    void similarTablesRanksExactCaseInsensitiveFirst() {
        List<String> similar = GraphQueryHints.similarTables(workspace(), "ORDERS", 3);
        assertEquals("app.orders", similar.get(0));
    }
}
