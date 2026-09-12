package com.sqlcli.cli;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.RelationCardinality;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.profile.ColumnProfile;
import com.sqlcli.profile.TableProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1：{@code schema value-domain} 剖析完顺手把外键可选性从「按 nullable 声明推断」
 * 升级成「按实测空值率」，{@code schema path} 的 INNER/LEFT 结论跟着变。
 *
 * <p>挡的错：外键列声明可空、实测空值率却为 0 时，只看声明会一律给 LEFT JOIN，
 * <b>多查出一批本该被过滤掉的行</b>——不报错，数偏多。
 *
 * <p>不连真库：采样那一段由 {@code TableProfilerTest} 覆盖，这里从
 * {@link SchemaActionCommand#proposeOptionality} 往后接，喂一份手搭的剖析结果。
 */
class SchemaValueDomainOptionalityTest {

    private static final String ALIAS = "optionality-cli";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private String previousHome;

    @BeforeEach
    void setUp() {
        // 变更会写运行库，指到临时目录，别碰用户真实的 ~/.sql-cli
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    @Test
    void profiledZeroNullsFlipsPathFromLeftToInner() throws Exception {
        seedNullableForeignKey();

        // 改之前：只有 nullable 声明，path 给 LEFT
        String before = runPath();
        assertTrue(before.contains("LEFT JOIN（推断"), before);

        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("value-domain");
        List<String> applied = new ArrayList<>();
        // 100 行全表采样、零空值：声明可空，实际从来没空过
        List<Long> approvals = command.proposeOptionality(store.load(ALIAS),
                profile("buyer_id", 100, 100), applied);

        assertTrue(approvals.isEmpty(), "别名没开图谱审批，当场落地，没有审批号");
        assertEquals(List.of("buyer_id 可选性"), applied);

        RelationWorkspaceEdge edge = store.load(ALIAS).getRelations().get(0);
        assertEquals(RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED,
                edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE));
        assertEquals(Boolean.FALSE, edge.getFromOptional());

        // 改之后：消费方读到的是同一个 attribute，结论翻成 INNER 且标明是实测的
        String after = runPath();
        assertTrue(after.contains("INNER JOIN（实测）"), after);
        assertFalse(after.contains("LEFT JOIN"), after);
    }

    /**
     * 同一条命令连提两条以上时，第二条不能撞乐观锁——{@code mutate} 落盘会把 revision
     * 顶上去，而命令手里那份工作区停在旧值。auto 别名（默认）上必然发生。
     */
    @Test
    void twoUpgradesInOneRunBothLand() throws Exception {
        seedNullableForeignKey();
        GraphWorkspace workspace = store.load(ALIAS);
        TableWorkspaceNode orders = workspace.getTableByQualifiedName("app.orders");
        orders.getColumns().add(ColumnWorkspaceNode.create("seller_id"));
        workspace.getRelations().add(foreignKey("seller_id"));
        store.save(workspace);

        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("value-domain");
        List<String> applied = new ArrayList<>();
        command.proposeOptionality(store.load(ALIAS), new TableProfile("app", "orders", 100, null,
                java.time.Instant.now(),
                List.of(column("buyer_id", 100, 100), column("seller_id", 100, 100))), applied);

        assertEquals(List.of("buyer_id 可选性", "seller_id 可选性"), applied);
        for (RelationWorkspaceEdge edge : store.load(ALIAS).getRelations()) {
            assertEquals(RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED,
                    edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE),
                    edge.getId());
        }
    }

    // ---------------------------------------------------------------- 辅助

    private void seedNullableForeignKey() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");

        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.getColumns().add(ColumnWorkspaceNode.create("buyer_id"));
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, "app", "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        workspace.getRelations().add(foreignKey("buyer_id"));
        store.save(workspace);
    }

    /** 导入时按 nullable 声明推断出来的样子：from 端可选、来源 inferred。 */
    private RelationWorkspaceEdge foreignKey(String column) {
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                GraphIds.columnId(ALIAS, "app", "orders", column),
                GraphIds.columnId(ALIAS, "app", "users", "id"), GraphActor.extractor);
        edge.setJoinExpression("app.orders." + column + " = app.users.id");
        edge.setCardinality(RelationCardinality.many_to_one);
        edge.setConfidence(1.0);
        edge.setVerified(true);
        edge.setStatus(GraphStatus.verified);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE,
                RelationWorkspaceEdge.OPTIONALITY_SOURCE_INFERRED);
        return edge;
    }

    private static ColumnProfile column(String name, long rows, long nonNull) {
        return new ColumnProfile(name, false, null, rows, nonNull,
                rows == 0 ? 0.0 : (double) (rows - nonNull) / rows, 10, false,
                List.of(), null, null, null, null, 0);
    }

    private static TableProfile profile(String column, long rows, long nonNull) {
        return new TableProfile("app", "orders", rows, null, java.time.Instant.now(),
                List.of(column(column, rows, nonNull)));
    }

    private String runPath() throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("path");
        command.setCommandArgs(List.of("path"));
        command.setTableName("app.orders");
        command.setTableName2("app.users");

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
