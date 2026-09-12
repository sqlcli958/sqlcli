package com.sqlcli.cli;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * search 文本输出的折叠行为：按表分组、表命中不展开全列、同表列命中折叠成摘要、条数上限。
 */
class SchemaSearchFoldTest {

    private static final String ALIAS = "fold-test";

    @TempDir Path temp;

    private GraphWorkspaceStore store;

    @BeforeEach
    void setUp() {
        store = new GraphWorkspaceStore(temp);
    }

    private void seed() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.setComment("订单主表");
        for (int i = 0; i < 6; i++) {
            ColumnWorkspaceNode column = ColumnWorkspaceNode.create("order_col_" + i);
            column.setComment("订单字段" + i);
            orders.getColumns().add(column);
        }
        ColumnWorkspaceNode plain = ColumnWorkspaceNode.create("remark");
        orders.getColumns().add(plain);
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode refund = TableWorkspaceNode.create(ALIAS, "app", "refund", GraphActor.extractor);
        ColumnWorkspaceNode refundNo = ColumnWorkspaceNode.create("order_no");
        refundNo.setComment("关联订单号");
        refund.getColumns().add(refundNo);
        workspace.getTables().put(refund.getId(), refund);

        store.save(workspace);
    }

    private String run(Consumer<SchemaActionCommand> configure) {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("search");
        command.setCommandArgs(java.util.List.of("search"));
        configure.accept(command);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            assertEquals(0, command.executeCommand());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void foldsColumnHitsUnderTheirTableAndNeverExpandsAllColumns() throws Exception {
        seed();
        String out = run(command -> command.setKeyword("订单"));

        // 表行一行说完：注释 + 列数，不展开全部列
        assertTrue(out.contains("[TABLE] app.orders"), out);
        assertTrue(out.contains("7列"), out);
        assertFalse(out.contains("Columns ("), "表命中不再展开全部列\n" + out);
        assertFalse(out.contains("remark"), "未命中的列不该出现\n" + out);

        // 同表命中列折叠成一行摘要，最多 3 个 + 剩余计数
        assertTrue(out.contains("命中列: "), out);
        assertTrue(out.contains("…另有3列命中"), out);

        // 另一张表的单列命中独立一行，注释直接跟在后面，不再有重复的 Comment: 行
        assertTrue(out.contains("[COLUMN] app.refund.order_no"), out);
        assertTrue(out.contains("关联订单号"), out);
        assertFalse(out.contains("Comment:"), "matchedText/注释不再重复打印\n" + out);
    }

    @Test
    void appliesGroupLimitWithFooter() throws Exception {
        seed();
        String out = run(command -> {
            command.setKeyword("订单");
            command.setSearchLimit(1);
        });

        assertTrue(out.contains("显示前 1 组"), out);
        assertTrue(out.contains("还有 1 组未显示"), out);
        assertFalse(out.contains("[COLUMN] app.refund.order_no"), "限 1 组时低分组不显示\n" + out);
    }

    /**
     * 术语命中要把映射目标一起给出来。
     *
     * <p>不给的话术语是条死路：Agent 搜到 `[TERM] 买家` 之后无处可去——不知道这个词对应
     * 哪张表，还得再猜一次或再敲一条命令。而术语在检索里权重最高，占着 Top1 却不给出口
     * 是最亏的一处。真实图谱里撞见过：5 条术语全部没挂 --map，搜到只能看见一个名字。
     */
    @Test
    void termHitShowsWhereItMapsTo() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        workspace.getTables().put(orders.getId(), orders);

        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "buyer", GraphActor.agent);
        term.setDisplayName("买家");
        workspace.getTerms().put(term.getId(), term);

        String columnId = GraphIds.columnId(ALIAS, "app", "orders", "buyer_id");
        workspace.getRelations().add(RelationWorkspaceEdge.create(
                ALIAS, RelationType.term_mapping, term.getId(), columnId, GraphActor.agent));
        store.save(workspace);

        String out = run(command -> command.setKeyword("buyer"));

        assertTrue(out.contains("[TERM]"), out);
        assertTrue(out.contains("→ app.orders.buyer_id"),
                "术语行要带上映射目标，否则 Agent 搜到它也不知道去哪张表\n" + out);
        assertFalse(out.contains("[未映射]"), "有映射就不该标未映射\n" + out);
    }

    /** 没挂 --map 的术语要明确标出来——它是纯负增益，不能看起来像一条正常结果。 */
    @Test
    void termWithoutMappingIsMarked() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        workspace.getTables().put(orders.getId(), orders);
        TermWorkspaceNode term = TermWorkspaceNode.create(ALIAS, "buyer", GraphActor.agent);
        workspace.getTerms().put(term.getId(), term);
        store.save(workspace);

        String out = run(command -> command.setKeyword("buyer"));

        assertTrue(out.contains("[TERM]"), out);
        assertTrue(out.contains("[未映射]"), out);
    }
}
