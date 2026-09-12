package com.sqlcli.cli;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.SemanticType;
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
 * {@code schema edit --business-name} / {@code --semantic-type}。
 *
 * <p>此前这条写路径是断的：Web UI 能写 businessName/semanticType
 * （见 WorkspaceMutationService#applyTablePatch / applyColumnPatch），CLI 只能读（search 结果）
 * 不能写，Agent 只能把读出来的语义塞进 --description 自由文本，而 PolicyEvaluator 的
 * semanticTypeAny 规则靠的是枚举值精确匹配，自由文本一条都命中不了。
 */
class SchemaEditBusinessSemanticTest {

    @TempDir
    Path temp;

    @Test
    void columnBusinessNameIsStoredAndReadBack() throws Exception {
        GraphWorkspaceStore store = workspaceWithBuyerIdColumn();

        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--business-name", "买家ID").executeCommand());

        assertEquals("买家ID", buyerId(store).getBusinessName());
    }

    @Test
    void columnSemanticTypeIsStoredAndReadBack() throws Exception {
        GraphWorkspaceStore store = workspaceWithBuyerIdColumn();

        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--semantic-type", "ref_id").executeCommand());

        assertEquals(SemanticType.ref_id, buyerId(store).getSemanticType());
    }

    @Test
    void blankSemanticTypeClearsIt() throws Exception {
        GraphWorkspaceStore store = workspaceWithBuyerIdColumn();
        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--semantic-type", "ref_id").executeCommand());

        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--semantic-type", "").executeCommand());

        assertNull(buyerId(store).getSemanticType());
    }

    @Test
    void invalidSemanticTypeIsRejectedAndListsAllLegalValues() throws Exception {
        GraphWorkspaceStore store = workspaceWithBuyerIdColumn();

        SchemaActionCommand command = edit(store, "--column", "app.orders.buyer_id",
                "--semantic-type", "mobile"); // 不是合法枚举值（正确写法是 phone）

        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        int exitCode;
        String stderr;
        System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
        try {
            exitCode = command.executeCommand();
        } finally {
            System.setErr(originalErr);
            stderr = captured.toString("UTF-8");
        }

        // 非法值必须失败（退出码非 0），不能被静默吞成 null 后当成功处理
        assertEquals(1, exitCode);
        // 没有被静默吞成 null——图谱里保持未设置状态，而不是"看起来改了、其实没改"
        assertNull(buyerId(store).getSemanticType());
        // 报错信息必须把全部合法取值列出来，Agent 不该对着一个 IllegalArgumentException 瞎猜
        for (SemanticType type : SemanticType.values()) {
            assertTrue(stderr.contains(type.name()), "missing " + type.name() + " in: " + stderr);
        }
    }

    @Test
    void tableBusinessNameIsStoredAndReadBack() throws Exception {
        GraphWorkspaceStore store = workspaceWithBuyerIdColumn();

        assertEquals(0, edit(store, "--table", "app.orders",
                "--business-name", "订单主表").executeCommand());

        assertEquals("订单主表", orders(store).getBusinessName());
    }

    @Test
    void existingDescriptionAndEnumValuesStillWork() throws Exception {
        // schema edit 的老行为（description / enum-values）不能被新增的两个选项破坏
        GraphWorkspaceStore store = workspaceWithBuyerIdColumn();

        assertEquals(0, edit(store, "--column", "app.orders.buyer_id",
                "--description", "买家ID", "--enum-values", "1=A,2=B").executeCommand());

        ColumnWorkspaceNode column = buyerId(store);
        assertEquals("买家ID", column.getDescription());
        assertNull(column.getComment());
        assertEquals(List.of("1=A", "2=B"), column.getValueHints().getEnumValues());
    }

    // ── helpers ──

    private GraphWorkspaceStore workspaceWithBuyerIdColumn() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        workspace.getTables().put(orders.getId(), orders);
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
                case "--description" -> command.setDescription(args[i + 1]);
                case "--enum-values" -> command.setEnumValuesCsv(args[i + 1]);
                case "--business-name" -> command.setEditBusinessName(args[i + 1]);
                case "--semantic-type" -> command.setEditSemanticType(args[i + 1]);
                default -> throw new IllegalArgumentException("unhandled: " + args[i]);
            }
        }
        return command;
    }

    private ColumnWorkspaceNode buyerId(GraphWorkspaceStore store) throws Exception {
        return orders(store).findColumn("buyer_id");
    }

    private TableWorkspaceNode orders(GraphWorkspaceStore store) throws Exception {
        GraphWorkspace reloaded = store.load("commands");
        TableWorkspaceNode orders = reloaded.getTableByQualifiedName("app.orders");
        assertNotNull(orders);
        return orders;
    }
}
