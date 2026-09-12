package com.sqlcli.graph.workspace;

import com.sqlcli.cli.SchemaActionCommand;
import com.sqlcli.graph.ui.service.WorkspaceLockManager;
import com.sqlcli.graph.ui.service.WorkspaceMutationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段语义的闸门（2026-08-27 决定，理由见 {@link ColumnWorkspaceNode} 类头）：
 * 不建候选态，闸门是 {@code verified} 位——Agent 写入不自封已确认，人在 UI 里改过即确认。
 *
 * <p>改之前 {@code schema edit} 无条件 {@code setVerified(true) / setConfidence(1.0)}，
 * 也就是 Agent 给自己盖章，而全仓没有一处读 {@code column.verified}——闸门的两个零件都在，
 * 一个被篡改、一个没人读。这个测试钉的就是这两半。
 */
class ColumnSemanticsGateTest {

    @TempDir
    Path temp;

    private static final String ALIAS = "gate-test";
    private static final String TABLE_ID = "table:" + ALIAS + ":app.orders";

    /** CLI 的 actor 是 agent：写得进去，但不许自封已确认。 */
    @Test
    void cliEditLeavesSemanticsUnconfirmed() throws Exception {
        GraphWorkspaceStore store = workspace();

        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--business-name", "买家ID").executeCommand());

        ColumnWorkspaceNode column = buyerId(store);
        assertEquals("买家ID", column.getBusinessName(), "值要立刻可读，闸门不是拦住写入");
        assertNull(column.getVerified(), "agent 不能给自己盖已确认章");
        assertNull(column.getConfidence());
        assertTrue(column.hasUnconfirmedSemantics());
    }

    /** 人在 UI 里改过，这一下就是确认动作本身，不另做一个「确认」按钮。 */
    @Test
    void uiEditConfirms() throws Exception {
        GraphWorkspaceStore store = workspace();
        WorkspaceMutationService service = new WorkspaceMutationService(
                store, new WorkspaceValidator(), new WorkspaceLockManager());

        WorkspaceMutationService.MutationResult result = service.updateColumnDescription(
                ALIAS, TABLE_ID, "buyer_id", Map.of("businessName", "买家ID"),
                store.load(ALIAS).getManifest().getRevision(), "人工确认");
        assertTrue(result.isSuccess(), String.valueOf(result.getErrors()));

        ColumnWorkspaceNode column = buyerId(store);
        assertEquals(Boolean.TRUE, column.getVerified());
        assertEquals(1.0, column.getConfidence());
        assertFalse(column.hasUnconfirmedSemantics());
    }

    /**
     * Agent 改了值，上一次人工确认就过期了——留着 verified=true 等于用旧值的确认给新值背书。
     */
    @Test
    void agentEditRevokesEarlierHumanConfirmation() throws Exception {
        GraphWorkspaceStore store = workspace();
        WorkspaceMutationService service = new WorkspaceMutationService(
                store, new WorkspaceValidator(), new WorkspaceLockManager());
        service.updateColumnDescription(ALIAS, TABLE_ID, "buyer_id", Map.of("businessName", "买家ID"),
                store.load(ALIAS).getManifest().getRevision(), "人工确认");
        assertEquals(Boolean.TRUE, buyerId(store).getVerified());

        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--business-name", "下单人ID").executeCommand());

        ColumnWorkspaceNode column = buyerId(store);
        assertEquals("下单人ID", column.getBusinessName());
        assertNull(column.getVerified(), "值变了，之前那次确认不该继续背书");
    }

    /** 结构事实（注释、类型、可空性）由导入维护，没有「确认」这回事，不该被算成待确认。 */
    @Test
    void structuralFactsAloneAreNotPendingConfirmation() {
        ColumnWorkspaceNode column = ColumnWorkspaceNode.create("id");
        column.setComment("主键");

        assertFalse(column.hasSemantics());
        assertFalse(column.hasUnconfirmedSemantics());
    }

    // ── helpers ──

    private GraphWorkspaceStore workspace() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace graph = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        graph.getTables().put(orders.getId(), orders);
        store.save(graph);
        return store;
    }

    private SchemaActionCommand edit(GraphWorkspaceStore store, String... args) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("edit");
        List<String> all = new java.util.ArrayList<>();
        all.add("edit");
        all.addAll(List.of(args));
        command.setCommandArgs(all);
        for (int i = 0; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "--column" -> command.setEditColumn(args[i + 1]);
                case "--business-name" -> command.setEditBusinessName(args[i + 1]);
                default -> throw new IllegalArgumentException("unhandled: " + args[i]);
            }
        }
        return command;
    }

    private ColumnWorkspaceNode buyerId(GraphWorkspaceStore store) throws Exception {
        return store.load(ALIAS).getTableByQualifiedName("app.orders").findColumn("buyer_id");
    }
}
