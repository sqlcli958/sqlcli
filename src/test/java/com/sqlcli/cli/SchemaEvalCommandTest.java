package com.sqlcli.cli;

import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `schema eval`：退出码、工作队列输出、结果落库。 */
class SchemaEvalCommandTest {

    private static final String ALIAS = "eval-cli";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private String previousHome;

    /** 评估结果落运行库，位置由 {@code sqlcli.home} 决定——不隔离就会写进开发机真实的 ~/.sql-cli。 */
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

    @Test
    void hardErrorMakesTheCommandExitOneAndPrintsAPastableFix() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        workspace.getTables().put(orders.getId(), orders);
        TermWorkspaceNode orphan = TermWorkspaceNode.create(ALIAS, "工单", GraphActor.agent);
        workspace.getTerms().put(orphan.getId(), orphan);
        store.save(workspace);

        Run run = run();

        assertEquals(1, run.exitCode(), "硬错误 > 0 退出码 1");
        assertTrue(run.stdout().contains("term.orphan"), run.stdout());
        assertTrue(run.stdout().contains("sql-cli " + ALIAS + " schema add-term 工单"),
                "报告即工作队列：每条发现后面必须跟着能直接粘的命令\n" + run.stdout());
        assertTrue(run.stdout().contains("覆盖率"), "覆盖率只报告不判定，但必须报");

        List<RunStateStore.GraphEvaluationRow> rows = runState().listGraphEvaluations(ALIAS, 10);
        assertEquals(1, rows.size(), "跑一次打印一堆数字不叫评估，结果必须入库");
        assertEquals("fail", rows.get(0).status());
        assertEquals(1, rows.get(0).errorCount());
        assertEquals(workspace.getManifest().getRevision(), rows.get(0).revision(),
                "评估结果绑 revision，否则没法比对两次评估");
        assertEquals(1, runState().countGraphFindings(rows.get(0).id()));
    }

    @Test
    void cleanGraphExitsZeroAndStillRecordsTheRun() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "mysql");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, "app", "orders", GraphActor.extractor);
        orders.setDescription("订单主表");
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);

        Run run = run();

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("没有硬错误"), run.stdout());
        assertEquals("pass", runState().listGraphEvaluations(ALIAS, 10).get(0).status());
    }

    @Test
    void missingWorkspaceIsAnError() {
        assertEquals(1, run().exitCode());
    }

    private RunStateStore runState() {
        return new RunStateStore(temp.resolve("run-state-home/sqlcli.db"),
                temp.resolve("run-state-home/execution-history"));
    }

    private record Run(int exitCode, String stdout, String stderr) {
    }

    private Run run() {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setAction("eval");
        command.setCommandArgs(List.of("eval"));

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            exitCode = command.executeCommand();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Run(exitCode, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }
}
