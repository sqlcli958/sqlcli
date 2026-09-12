package com.sqlcli.cli;

import com.sqlcli.graph.workspace.ColumnValueHints;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.runstate.RunStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `schema data-quality` 的命令这一层：授权闸门、退出码、结果落库。
 *
 * <p>连库那一段直接喂一条内存 SQLite（{@code reportDataQuality} 收 Connection），
 * 不为跑一次端到端去配别名，也不碰任何真实数据库。
 */
class SchemaDataQualityCommandTest {

    private static final String ALIAS = "dq-cli";
    private static final String SCHEMA = "main";

    @TempDir Path temp;

    private GraphWorkspaceStore store;
    private String previousHome;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        store = new GraphWorkspaceStore(temp);
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE orders (id INTEGER, status TEXT, memo TEXT)");
            for (int i = 0; i < 40; i++) {
                st.execute("INSERT INTO orders VALUES (" + i + ", '" + (i == 0 ? "9" : "0") + "', NULL)");
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        if (previousHome == null) System.clearProperty("sqlcli.home");
        else System.setProperty("sqlcli.home", previousHome);
    }

    @Test
    void hardErrorExitsOneAndTheRunLandsInTheEvaluationTable() throws Exception {
        GraphWorkspace workspace = seed();
        SchemaActionCommand command = command();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = capturing(stdout, () -> command.reportDataQuality(connection, "sqlite", workspace,
                workspace.getTableByQualifiedName(SCHEMA + ".orders"), 12));

        assertEquals(1, exitCode, "data.enum-undeclared 是硬错误");
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("data.enum-undeclared"), text);
        assertTrue(text.contains("schema value-domain"), "每条发现后面必须跟着能直接粘的命令\n" + text);

        List<RunStateStore.GraphEvaluationRow> rows = runState().listGraphEvaluations(ALIAS, 10);
        assertEquals(1, rows.size(), "跑一次打印一堆结论不叫探针，结果必须入库");
        assertEquals("data-quality", rows.get(0).source(),
                "source 要能把它和 schema eval 的点分开——两者的指标口径不同，混着连线会骗人");
        assertEquals("fail", rows.get(0).status());
        assertEquals(1, rows.get(0).errorCount());
        // memo 全 NULL 那条是 warning，不判失败但要记
        assertTrue(rows.get(0).warningCount() >= 1, "warning 数=" + rows.get(0).warningCount());
        assertNull(rows.get(0).metricsJson(), "数据层没有覆盖率，硬凑一个百分比就是虚荣指标");
    }

    /** 没有 error 时退出码 0——warning 是「值得看」，不是「一定错」。 */
    @Test
    void warningsAloneStillExitZero() throws Exception {
        GraphWorkspace workspace = seed();
        // 摘掉图谱里的值域声明，enum-undeclared 就没有比对基准了，只剩 memo 那条 warning
        workspace.getTableByQualifiedName(SCHEMA + ".orders").findColumn("status").setValueHints(null);
        SchemaActionCommand command = command();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = capturing(stdout, () -> command.reportDataQuality(connection, "sqlite", workspace,
                workspace.getTableByQualifiedName(SCHEMA + ".orders"), 12));

        assertEquals(0, exitCode);
        assertEquals("pass", runState().listGraphEvaluations(ALIAS, 10).get(0).status());
    }

    /**
     * 授权闸门：非交互环境下没给 --yes 就不跑，而且要先把将要执行的查询打出来——
     * 授权的对象是那些查询，不是一个命令名（skill 铁律 2）。
     */
    @Test
    void withoutYesItPrintsThePlanAndRefusesToConnect() throws Exception {
        seed();
        SchemaActionCommand command = command();
        command.setAction("data-quality");
        command.setEditTable(SCHEMA + ".orders");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try (PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setErr(err);
            exitCode = command.executeCommand();
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(2, exitCode);
        String text = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("SELECT COUNT(*)"), "清单里必须有真正会跑的 SQL\n" + text);
        assertTrue(text.contains("--yes"), text);
    }

    @Test
    void tableIsRequired() {
        seedQuietly();
        SchemaActionCommand command = command();
        command.setAction("data-quality");

        assertEquals(1, command.executeCommand());
    }

    @Test
    void missingWorkspaceIsAnError() {
        SchemaActionCommand command = command();
        command.setAction("data-quality");
        command.setEditTable(SCHEMA + ".orders");

        assertEquals(1, command.executeCommand());
    }

    // ------------------------------------------------------------------------ 辅助

    private SchemaActionCommand command() {
        SchemaActionCommand command = new SchemaActionCommand(store);
        command.setAlias(ALIAS);
        command.setCommandArgs(List.of("data-quality"));
        return command;
    }

    private GraphWorkspace seed() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "sqlite");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, SCHEMA, "orders", GraphActor.extractor);
        for (String name : List.of("id", "status", "memo")) {
            orders.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        ColumnValueHints hints = new ColumnValueHints();
        hints.setEnumValues(new ArrayList<>(List.of("0=新建", "1=完成")));
        orders.findColumn("status").setValueHints(hints);
        workspace.getTables().put(orders.getId(), orders);
        store.save(workspace);
        return store.load(ALIAS);
    }

    private void seedQuietly() {
        try {
            seed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RunStateStore runState() {
        return new RunStateStore(temp.resolve("home/sqlcli.db"), temp.resolve("home/execution-history"));
    }

    private interface Body {
        int run() throws Exception;
    }

    private static int capturing(ByteArrayOutputStream stdout, Body body) throws Exception {
        PrintStream originalOut = System.out;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            return body.run();
        } finally {
            System.setOut(originalOut);
        }
    }
}
