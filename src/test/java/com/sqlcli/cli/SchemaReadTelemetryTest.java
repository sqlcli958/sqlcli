package com.sqlcli.cli;

import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.runstate.InteractionDetail;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读侧遥测的消费侧闭环：{@code describe} 报「被搜到 N 次」、{@code path} 记两端命中、
 * {@code gaps} 按读取次数排缺口 + 单列搜空的查询词。
 */
class SchemaReadTelemetryTest {

    private static final String ALIAS = "read-telemetry";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("run-state-home").toString());
        store = new GraphWorkspaceStore(temp);
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    private RunStateStore runState() {
        return new RunStateStore(temp.resolve("run-state-home/sqlcli.db"),
                temp.resolve("run-state-home/execution-history"));
    }

    private static TableWorkspaceNode table(String schema, String name) {
        return TableWorkspaceNode.create(ALIAS, schema, name, GraphActor.extractor);
    }

    @Test
    void describePrintsPriorSearchHitCountAndThenRecordsItsOwnRead() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = table("app", "orders");
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        // 预先模拟 12 次搜索命中——这条数据本该由 `schema search` 写，这里直接落库
        // 模拟另一个 agent 接上埋点之后的样子。
        runState().recordInteraction(ALIAS, "schema search", "ok", 0, null, null,
                InteractionDetail.of("订单", 1, 0.9, List.of(orders.getId())));
        for (int i = 0; i < 11; i++) {
            runState().recordInteraction(ALIAS, "schema search", "ok", 0, null, null,
                    InteractionDetail.of("订单" + i, 1, 0.5, List.of(orders.getId())));
        }

        String stdout = describe("app.orders");

        assertTrue(stdout.contains("被搜到 12 次"), stdout);

        // describe 自己这次调用记了一条 describe 类型的读，不污染 search 计数
        assertEquals(12, runState().countTargetHits(ALIAS, "schema search", orders.getId()));
        assertEquals(1, runState().countTargetHits(ALIAS, "schema describe", orders.getId()));
    }

    @Test
    void describeWithoutPriorSearchesReportsZeroHitsNotAnError() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = table("app", "orders");
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        String stdout = describe("app.orders");

        assertTrue(stdout.contains("被搜到 0 次"), stdout);
    }

    @Test
    void pathRecordsBothEndpointsAsAGraphRead() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = table("app", "orders");
        TableWorkspaceNode users = table("app", "users");
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);
        store.save(workspace);

        path("app.orders", "app.users");

        assertEquals(1, runState().countTargetHits(ALIAS, "schema path", orders.getId()));
        assertEquals(1, runState().countTargetHits(ALIAS, "schema path", users.getId()));
    }

    @Test
    void gapsRanksReadButUncuratedTablesAndListsMissedSearchQueries() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = table("app", "orders"); // 被读过、没人整理——该出现
        TableWorkspaceNode users = table("app", "users");
        users.setDescription("用户主表"); // 被读过、已经整理——不该出现
        TableWorkspaceNode ghosts = table("app", "ghosts"); // 从没被读过——不该出现
        workspace.getTables().put(orders.getId(), orders);
        workspace.getTables().put(users.getId(), users);
        workspace.getTables().put(ghosts.getId(), ghosts);
        store.save(workspace);

        RunStateStore runState = runState();
        runState.recordInteraction(ALIAS, "schema describe", "ok", 0, null, null,
                InteractionDetail.of("app.orders", 1, null, List.of(orders.getId())));
        runState.recordInteraction(ALIAS, "schema describe", "ok", 0, null, null,
                InteractionDetail.of("app.orders", 1, null, List.of(orders.getId())));
        runState.recordInteraction(ALIAS, "schema describe", "ok", 0, null, null,
                InteractionDetail.of("app.users", 1, null, List.of(users.getId())));
        runState.recordInteraction(ALIAS, "schema search", "ok", 0, null, null,
                InteractionDetail.of("退款单", 0, null, List.of()));
        runState.recordInteraction(ALIAS, "schema search", "ok", 0, null, null,
                InteractionDetail.of("退款单", 0, null, List.of()));

        String stdout = gaps();

        assertTrue(stdout.contains("app.orders"), stdout);
        assertTrue(stdout.contains("被读取 2 次"), stdout);
        assertTrue(stdout.contains("sql-cli " + ALIAS + " schema edit --table app.orders"), stdout);
        assertFalse(stdout.contains("app.users"), stdout);
        assertFalse(stdout.contains("app.ghosts"), stdout);
        assertTrue(stdout.contains("\"退款单\" 搜空 2 次"), stdout);
    }

    @Test
    void gapsWithNoTelemetryYetSaysSoInsteadOfAnEmptyList() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        workspace.getTables().put(table("app", "orders").getId(), table("app", "orders"));
        store.save(workspace);

        String stdout = gaps();

        assertTrue(stdout.contains("没有缺口"), stdout);
    }

    private String describe(String tableName) throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("describe");
        command.setTableName(tableName);
        return capture(command);
    }

    private String path(String table1, String table2) throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("path");
        command.setTableName(table1);
        command.setTableName2(table2);
        return capture(command);
    }

    private String gaps() throws Exception {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("gaps");
        command.setCommandArgs(List.of("gaps"));
        return capture(command);
    }

    /**
     * 跑一条命令并落交互记录。
     *
     * <p><b>这里必须模拟 {@code SqlCli.run} 的收口</b>：命令自己只把命中明细放进
     * {@link InteractionDetail}，真正写库的是入口——因为只有入口知道这次成没成。
     * 测试直接构造命令（要塞临时目录的 store），走不到真入口，所以在这里补上同一步。
     *
     * <p>action 名也得跟 {@code SqlCli.actionOf} 的产出一致（{@code "schema " + 动作}）。
     * 两边对不上时 {@code describe} 的「被搜到 N 次」会永远是 0 且不报错——
     * 这个错真发生过一次，就是靠下面那条断言抓住的。
     */
    private String capture(SchemaActionCommand command) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            exitCode = command.executeCommand();
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
            runState().recordInteraction(ALIAS, "schema " + command.getAction(),
                    "ok", 0, null, null, InteractionDetail.take());
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
