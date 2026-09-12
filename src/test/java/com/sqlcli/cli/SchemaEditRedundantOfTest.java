package com.sqlcli.cli;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code schema edit --column --redundant-of}：函数依赖 / 权威来源标注（P1 第 4 条）。
 *
 * <p>覆盖 dev-checklist 列出的验收点：标注能写入/读回/落盘后仍在、权威源指向不存在的列时
 * 报错、search 命中冗余列时提示权威源（后半段见 {@link SearchRedundantOfTest}，
 * 那部分不经过 CLI 参数解析，直接测搜索引擎更直接）。
 */
class SchemaEditRedundantOfTest {

    @TempDir
    Path temp;

    @Test
    void redundantOfIsStoredAndReadBack() throws Exception {
        GraphWorkspaceStore store = workspaceWithOrdersAndCustomer();

        assertEquals(0, edit(store, "--column", "app.orders.customer_name",
                "--redundant-of", "app.customer.name").executeCommand());

        ColumnWorkspaceNode customerName = column(store, "app.orders", "customer_name");
        String customerNameColumnId = column(store, "app.customer", "name")
                .computeId("commands", "app", "customer");
        assertEquals(customerNameColumnId, customerName.getRedundantOf());
    }

    @Test
    void blankRedundantOfClearsIt() throws Exception {
        GraphWorkspaceStore store = workspaceWithOrdersAndCustomer();
        assertEquals(0, edit(store, "--column", "app.orders.customer_name",
                "--redundant-of", "app.customer.name").executeCommand());

        assertEquals(0, edit(store, "--column", "app.orders.customer_name",
                "--redundant-of", "").executeCommand());

        assertNull(column(store, "app.orders", "customer_name").getRedundantOf());
    }

    @Test
    void danglingRedundantOfTargetIsRejected() throws Exception {
        GraphWorkspaceStore store = workspaceWithOrdersAndCustomer();

        SchemaActionCommand command = edit(store, "--column", "app.orders.customer_name",
                "--redundant-of", "app.customer.does_not_exist");

        int exitCode = runCapturingStderr(command);

        assertEquals(1, exitCode);
        // 失败必须是没写入，不能"报了错但其实已经落盘"
        assertNull(column(store, "app.orders", "customer_name").getRedundantOf());
    }

    @Test
    void selfReferenceIsRejected() throws Exception {
        GraphWorkspaceStore store = workspaceWithOrdersAndCustomer();

        SchemaActionCommand command = edit(store, "--column", "app.orders.customer_name",
                "--redundant-of", "app.orders.customer_name");

        assertEquals(1, runCapturingStderr(command));
        assertNull(column(store, "app.orders", "customer_name").getRedundantOf());
    }

    @Test
    void survivesReloadFromDisk() throws Exception {
        GraphWorkspaceStore store = workspaceWithOrdersAndCustomer();
        assertEquals(0, edit(store, "--column", "app.orders.customer_name",
                "--redundant-of", "app.customer.name").executeCommand());

        // 新开一个 store 指向同一目录，模拟"进程重启后再读"
        GraphWorkspaceStore reopened = new GraphWorkspaceStore(temp);
        ColumnWorkspaceNode reloaded = column(reopened, "app.orders", "customer_name");
        assertNotNull(reloaded.getRedundantOf());
        assertTrue(reloaded.getRedundantOf().endsWith("app.customer.name"));
    }

    // ── helpers ──

    private GraphWorkspaceStore workspaceWithOrdersAndCustomer() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("customer_name"));
        TableWorkspaceNode customer = TableWorkspaceNode.create(
                "commands", "app", "customer", GraphActor.extractor);
        customer.getColumns().add(ColumnWorkspaceNode.create("name"));
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(customer.getId(), customer);
        store.save(workspace);
        return store;
    }

    private SchemaActionCommand edit(GraphWorkspaceStore store, String... args) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias("commands");
        command.setAction("edit");
        List<String> all = new java.util.ArrayList<>();
        all.add("edit");
        all.addAll(List.of(args));
        command.setCommandArgs(all);
        for (int i = 0; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "--table" -> command.setEditTable(args[i + 1]);
                case "--column" -> command.setEditColumn(args[i + 1]);
                case "--redundant-of" -> command.setEditRedundantOf(args[i + 1]);
                default -> throw new IllegalArgumentException("unhandled: " + args[i]);
            }
        }
        return command;
    }

    private int runCapturingStderr(SchemaActionCommand command) throws Exception {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
        try {
            return command.executeCommand();
        } finally {
            System.setErr(originalErr);
        }
    }

    private ColumnWorkspaceNode column(GraphWorkspaceStore store, String qualifiedTable, String columnName)
            throws Exception {
        GraphWorkspace reloaded = store.load("commands");
        TableWorkspaceNode table = reloaded.getTableByQualifiedName(qualifiedTable);
        assertNotNull(table, "table not found: " + qualifiedTable);
        ColumnWorkspaceNode column = table.findColumn(columnName);
        assertNotNull(column, "column not found: " + columnName);
        return column;
    }
}
