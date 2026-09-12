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
 * {@code schema edit} 的值域写入路径。
 *
 * <p>这条路径此前是断的：{@code ColumnValueHints} 的集合字段没有初始化，
 * {@code --example} 一用就 NPE，而 {@code --enum-values} 根本不存在——
 * 于是 {@code status_field_dictionary} 规则读的那个字段永远没人能写。
 */
class SchemaEditValueHintsTest {

    @TempDir
    Path temp;

    @Test
    void enumValuesAreStoredAsValueEqualsLabel() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();

        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--enum-values", "0=待付款, 1=已付款 ,2").executeCommand());

        List<String> stored = status(store).getValueHints().getEnumValues();
        assertEquals(List.of("0=待付款", "1=已付款", "2"), stored);
    }

    @Test
    void enumValuesReplaceRatherThanAppend() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();
        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--enum-values", "0=旧,1=值").executeCommand());

        // 值域是封闭集合：域变了要的是新的域，不是并集
        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--enum-values", "9=新值").executeCommand());

        assertEquals(List.of("9=新值"), status(store).getValueHints().getEnumValues());
    }

    @Test
    void emptyEnumValuesClearTheDomain() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();
        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--enum-values", "1=有").executeCommand());

        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--enum-values", "").executeCommand());

        assertTrue(status(store).getValueHints().getEnumValues().isEmpty());
    }

    @Test
    void duplicateEnumValueIsRejected() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();

        // 同一个值给两个含义是写的人搞错了；静默取一个会让 Agent 一直照错的生成 SQL
        assertEquals(1, edit(store, "--column", "app.orders.status",
                "--enum-values", "1=已付款,1=已发货").executeCommand());

        assertNull(status(store).getValueHints());
    }

    @Test
    void exampleAppendsSampleValuesWithoutNpe() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();

        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--example", "PO20260822").executeCommand());
        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--example", "PO20260823").executeCommand());
        // 重复的样例不再追加一遍
        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--example", "PO20260823").executeCommand());

        assertEquals(List.of("PO20260822", "PO20260823"),
                status(store).getValueHints().getSampleValues());
    }

    @Test
    void formatIsStoredAndClearable() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();

        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--format", "SM4 密文，前缀 ENC#240606#").executeCommand());
        assertEquals("SM4 密文，前缀 ENC#240606#", status(store).getValueHints().getFormat());

        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--format", "").executeCommand());
        assertNull(status(store).getValueHints().getFormat());
    }

    @Test
    void columnsWithoutHintsKeepValueHintsNull() throws Exception {
        GraphWorkspaceStore store = workspaceWithStatusColumn();

        assertEquals(0, edit(store, "--column", "app.orders.status",
                "--description", "订单状态").executeCommand());

        // 九千多个字段每个挂一个空 valueHints 是白白撑大工作区文件
        assertNull(status(store).getValueHints());
    }

    // ── helpers ──

    private GraphWorkspaceStore workspaceWithStatusColumn() throws Exception {
        GraphWorkspaceStore store = new GraphWorkspaceStore(temp);
        GraphWorkspace workspace = GraphWorkspace.create("commands", "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(
                "commands", "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("status"));
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
                case "--column" -> command.setEditColumn(args[i + 1]);
                case "--enum-values" -> command.setEnumValuesCsv(args[i + 1]);
                case "--format" -> command.setValueFormat(args[i + 1]);
                case "--example" -> command.setExample(args[i + 1]);
                case "--description" -> command.setDescription(args[i + 1]);
                default -> throw new IllegalArgumentException("unhandled: " + args[i]);
            }
        }
        return command;
    }

    private ColumnWorkspaceNode status(GraphWorkspaceStore store) throws Exception {
        GraphWorkspace reloaded = store.load("commands");
        TableWorkspaceNode orders = reloaded.getTableByQualifiedName("app.orders");
        assertNotNull(orders);
        return orders.findColumn("status");
    }
}
